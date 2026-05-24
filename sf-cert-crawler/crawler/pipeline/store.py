from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from sqlalchemy import (
    Boolean,
    DateTime,
    Engine,
    Float,
    Integer,
    JSON,
    LargeBinary,
    String,
    Text,
    create_engine,
    func,
    select,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column

from ..config import settings
from ..logging_setup import get_logger
from ..models import Question
from .dedup import NearDup, pack_embedding, semantic_dedup, unpack_embedding

if TYPE_CHECKING:
    from .dedup import Embedder

log = get_logger(__name__)


class Base(DeclarativeBase):
    pass


class QuestionRow(Base):
    __tablename__ = "questions"

    id: Mapped[str] = mapped_column(String(16), primary_key=True)
    cert: Mapped[str] = mapped_column(String(64), index=True)
    topic: Mapped[str | None] = mapped_column(String(128), nullable=True)
    question_text: Mapped[str] = mapped_column(Text)
    options: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    correct_answer: Mapped[str | None] = mapped_column(Text, nullable=True)
    explanation: Mapped[str | None] = mapped_column(Text, nullable=True)
    difficulty: Mapped[str | None] = mapped_column(String(16), nullable=True)
    source_url: Mapped[str] = mapped_column(Text)
    source_type: Mapped[str] = mapped_column(String(32))
    source_license_note: Mapped[str] = mapped_column(Text)
    extracted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    confidence: Mapped[float] = mapped_column(Float)
    embedding: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    # --- quiz-app push lifecycle (set by `push`) ---
    question_type: Mapped[str | None] = mapped_column(String(8), nullable=True)  # SINGLE | MULTI
    quality_ok: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    quality_issues: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    pushed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class RunRow(Base):
    __tablename__ = "runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    agent: Mapped[str] = mapped_column(String(16), default="external")  # external | internal
    mode: Mapped[str] = mapped_column(String(16))  # run | ingest | push
    source: Mapped[str | None] = mapped_column(String(64), nullable=True)
    cert: Mapped[str] = mapped_column(String(64))
    discovered: Mapped[int] = mapped_column(Integer, default=0)
    fetched: Mapped[int] = mapped_column(Integer, default=0)
    filtered_dropped: Mapped[int] = mapped_column(Integer, default=0)
    filtered_review: Mapped[int] = mapped_column(Integer, default=0)
    kept: Mapped[int] = mapped_column(Integer, default=0)
    extracted: Mapped[int] = mapped_column(Integer, default=0)
    inserted: Mapped[int] = mapped_column(Integer, default=0)
    skipped_exact: Mapped[int] = mapped_column(Integer, default=0)
    skipped_near: Mapped[int] = mapped_column(Integer, default=0)
    pushed: Mapped[int] = mapped_column(Integer, default=0)
    llm_calls: Mapped[int] = mapped_column(Integer, default=0)
    llm_cost_usd: Mapped[float] = mapped_column(Float, default=0.0)
    status: Mapped[str] = mapped_column(String(16), default="ok")  # ok | error | budget
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


@dataclass
class InsertResult:
    inserted: int = 0
    skipped_exact: int = 0
    skipped_near: int = 0
    near_dups: list[NearDup] = field(default_factory=list)


_QUESTIONS_NEW_COLUMNS = {
    "embedding": "BLOB",
    "question_type": "VARCHAR(8)",
    "quality_ok": "BOOLEAN",
    "quality_issues": "JSON",
    "pushed_at": "DATETIME",
}
_RUNS_NEW_COLUMNS = {
    "agent": "VARCHAR(16)",
    "pushed": "INTEGER DEFAULT 0",
}


def get_engine() -> Engine:
    return create_engine(f"sqlite:///{settings.db_path}", future=True)


def init_db(engine: Engine | None = None) -> Engine:
    engine = engine or get_engine()
    Base.metadata.create_all(engine)
    _ensure_schema(engine)
    return engine


def _ensure_schema(engine: Engine) -> None:
    """Lightweight forward migration: add columns introduced after the first schema."""
    with engine.begin() as conn:
        for table, cols in (("questions", _QUESTIONS_NEW_COLUMNS), ("runs", _RUNS_NEW_COLUMNS)):
            existing = {row[1] for row in conn.exec_driver_sql(f"PRAGMA table_info({table})")}
            if not existing:
                continue  # table doesn't exist yet -> create_all built it with all columns
            for name, ddl in cols.items():
                if name not in existing:
                    log.info("migrating '%s' table: adding '%s' column", table, name)
                    conn.exec_driver_sql(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}")


# --- questions ------------------------------------------------------------------


def insert_questions(
    questions: list[Question],
    *,
    engine: Engine | None = None,
    embedder: "Embedder | None" = None,
    similarity_threshold: float = 0.92,
) -> InsertResult:
    """Insert questions, deduplicating.

    Exact-hash dedup (by ``id``) always runs. Semantic (near-duplicate) dedup
    runs only when an ``embedder`` is supplied.
    """
    engine = init_db(engine)
    result = InsertResult()
    with Session(engine) as session:
        fresh: list[Question] = []
        for q in questions:
            if session.get(QuestionRow, q.id) is not None:
                result.skipped_exact += 1
            else:
                fresh.append(q)

        if embedder is None:
            for q in fresh:
                session.add(QuestionRow(**q.model_dump()))
            result.inserted = len(fresh)
            session.commit()
            return result

        rows = list(session.scalars(select(QuestionRow)))
        missing = [r for r in rows if r.embedding is None]
        if missing:
            log.info("backfilling embeddings for %d existing question(s)", len(missing))
            for row, vector in zip(missing, embedder([r.question_text for r in missing])):
                row.embedding = pack_embedding(vector)
            session.flush()
        existing: list[tuple[str, list[float]]] = []
        for row in rows:
            vec = unpack_embedding(row.embedding)
            if vec is not None:
                existing.append((row.id, vec))
        ded = semantic_dedup(fresh, existing=existing, embedder=embedder, threshold=similarity_threshold)
        for q, vector in ded.unique:
            row = QuestionRow(**q.model_dump())
            row.embedding = pack_embedding(vector)
            session.add(row)
        result.inserted = len(ded.unique)
        result.skipped_near = len(ded.near_duplicates)
        result.near_dups = ded.near_duplicates
        session.commit()
    return result


def recent_questions(*, cert: str | None = None, limit: int = 20, engine: Engine | None = None) -> list[QuestionRow]:
    engine = init_db(engine)
    stmt = select(QuestionRow)
    if cert is not None:
        stmt = stmt.where(QuestionRow.cert == cert)
    stmt = stmt.order_by(QuestionRow.extracted_at.desc()).limit(limit)
    with Session(engine) as session:
        return list(session.scalars(stmt))


def count_questions(*, cert: str | None = None, engine: Engine | None = None) -> int:
    engine = init_db(engine)
    stmt = select(func.count()).select_from(QuestionRow)
    if cert is not None:
        stmt = stmt.where(QuestionRow.cert == cert)
    with Session(engine) as session:
        return int(session.scalar(stmt) or 0)


# --- quiz-app push lifecycle ----------------------------------------------------


def unpushed_questions(*, cert: str | None = None, limit: int | None = None, engine: Engine | None = None) -> list[QuestionRow]:
    """Questions not yet pushed to the quiz app, oldest first."""
    engine = init_db(engine)
    stmt = select(QuestionRow).where(QuestionRow.pushed_at.is_(None))
    if cert is not None:
        stmt = stmt.where(QuestionRow.cert == cert)
    stmt = stmt.order_by(QuestionRow.extracted_at.asc())
    if limit is not None:
        stmt = stmt.limit(limit)
    with Session(engine) as session:
        return list(session.scalars(stmt))


def record_quality(question_id: str, *, ok: bool, issues: list[str], question_type: str, engine: Engine | None = None) -> None:
    engine = init_db(engine)
    with Session(engine) as session:
        row = session.get(QuestionRow, question_id)
        if row is None:
            return
        row.quality_ok = ok
        row.quality_issues = issues
        row.question_type = question_type
        session.commit()


def mark_pushed(question_ids: list[str], *, engine: Engine | None = None) -> None:
    engine = init_db(engine)
    now = datetime.now(timezone.utc)
    with Session(engine) as session:
        for qid in question_ids:
            row = session.get(QuestionRow, qid)
            if row is not None:
                row.pushed_at = now
        session.commit()


# --- run tracking ---------------------------------------------------------------


def start_run(*, agent: str, mode: str, source: str | None, cert: str, engine: Engine | None = None) -> int:
    engine = init_db(engine)
    with Session(engine) as session:
        row = RunRow(started_at=datetime.now(timezone.utc), agent=agent, mode=mode, source=source, cert=cert)
        session.add(row)
        session.flush()
        run_id = row.id
        session.commit()
        return run_id


def finish_run(run_id: int, *, engine: Engine | None = None, **fields: object) -> None:
    engine = init_db(engine)
    with Session(engine) as session:
        row = session.get(RunRow, run_id)
        if row is None:
            return
        row.finished_at = datetime.now(timezone.utc)
        for key, value in fields.items():
            if hasattr(row, key):
                setattr(row, key, value)
        session.commit()


def recent_runs(*, limit: int = 20, engine: Engine | None = None) -> list[RunRow]:
    engine = init_db(engine)
    stmt = select(RunRow).order_by(RunRow.id.desc()).limit(limit)
    with Session(engine) as session:
        return list(session.scalars(stmt))
