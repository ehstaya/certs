from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, Field

# Allowed values for RawItem.source_type / Question.source_type.
SOURCE_TYPES = ("reddit", "trailhead", "blog", "other")

Difficulty = Literal["easy", "medium", "hard"]


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class RawItem(BaseModel):
    """A candidate document a Source has discovered, before fetch/filter/extract."""

    source_name: str
    source_type: str  # one of SOURCE_TYPES
    url: str
    title: str | None = None
    discovered_at: datetime = Field(default_factory=_utcnow)
    # Source-specific extras (e.g. Drive file id + mime type, local file path).
    meta: dict[str, str] = Field(default_factory=dict)


class FetchedDoc(BaseModel):
    """The raw body of a fetched URL plus minimal response metadata."""

    url: str
    status_code: int
    content_type: str | None
    text: str
    from_cache: bool


class Question(BaseModel):
    """A single study question. Mirrors the storage row in `pipeline.store`.

    ``correct_answer`` and ``explanation`` are only filled when the source
    states them explicitly — we never invent answers. ``confidence`` is 1.0 for
    deterministic extraction from structured content; the LLM path (phase 4)
    sets it lower when unsure.
    """

    id: str  # sha256 of the normalized question text, first 16 hex chars
    cert: str  # "admin", ...
    topic: str | None = None
    question_text: str
    options: list[str] | None = None  # None for open-ended / scenario prompts
    correct_answer: str | None = None
    explanation: str | None = None
    difficulty: Difficulty | None = None
    source_url: str
    source_type: str  # one of SOURCE_TYPES
    source_license_note: str
    extracted_at: datetime = Field(default_factory=_utcnow)
    confidence: float = Field(ge=0.0, le=1.0)
