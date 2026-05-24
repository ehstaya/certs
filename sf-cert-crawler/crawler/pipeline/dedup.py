from __future__ import annotations

import array
import math
from dataclasses import dataclass, field
from typing import Callable

from ..logging_setup import get_logger
from ..models import Question

log = get_logger(__name__)

DEFAULT_SIMILARITY_THRESHOLD = 0.92
EMBEDDING_MODEL = "sentence-transformers/all-MiniLM-L6-v2"

# Maps a list of texts to a list of (unit-norm) embedding vectors.
Embedder = Callable[[list[str]], list[list[float]]]


class SemanticDedupUnavailable(RuntimeError):
    """sentence-transformers isn't installed, so semantic dedup can't run."""


def default_embedder() -> Embedder:
    """An embedder backed by a local `sentence-transformers/all-MiniLM-L6-v2`.

    Chosen over a hosted embedding API (e.g. Voyage): dedup only needs a cheap
    "is this the same question?" similarity signal, the quality gap doesn't
    matter here, and a local model has zero marginal cost and needs no API key.
    """
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:  # pragma: no cover — exercised only without the extra installed
        raise SemanticDedupUnavailable(
            "sentence-transformers is not installed — run "
            "`pip install 'sf-cert-crawler[dedup]'` (or `pip install sentence-transformers`) "
            "to enable semantic dedup, or omit --semantic-dedup"
        ) from exc
    model = SentenceTransformer(EMBEDDING_MODEL)

    def embed(texts: list[str]) -> list[list[float]]:
        vectors = model.encode(list(texts), normalize_embeddings=True, show_progress_bar=False)
        return [[float(x) for x in vector] for vector in vectors]

    return embed


def pack_embedding(vector: list[float]) -> bytes:
    return array.array("f", vector).tobytes()


def unpack_embedding(blob: bytes | None) -> list[float] | None:
    if not blob:
        return None
    arr = array.array("f")
    arr.frombytes(blob)
    return list(arr)


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(x * x for x in b)) or 1.0
    return dot / (na * nb)


@dataclass
class NearDup:
    question: Question
    matched_id: str
    similarity: float


@dataclass
class DedupResult:
    unique: list[tuple[Question, list[float]]] = field(default_factory=list)
    near_duplicates: list[NearDup] = field(default_factory=list)


def semantic_dedup(
    candidates: list[Question],
    *,
    existing: list[tuple[str, list[float]]],
    embedder: Embedder,
    threshold: float = DEFAULT_SIMILARITY_THRESHOLD,
) -> DedupResult:
    """Split ``candidates`` into ones to insert vs. near-duplicates of an
    already-stored question (or of an earlier candidate in the same batch).

    ``existing`` is ``(question_id, embedding)`` pairs for stored questions.
    """
    result = DedupResult()
    if not candidates:
        return result
    candidate_vectors = embedder([q.question_text for q in candidates])
    pool: list[tuple[str, list[float]]] = list(existing)
    for question, vector in zip(candidates, candidate_vectors):
        best_id: str | None = None
        best_sim = -1.0
        for other_id, other_vec in pool:
            sim = cosine_similarity(vector, other_vec)
            if sim > best_sim:
                best_sim, best_id = sim, other_id
        if best_id is not None and best_sim >= threshold:
            result.near_duplicates.append(NearDup(question=question, matched_id=best_id, similarity=best_sim))
        else:
            result.unique.append((question, vector))
            pool.append((question.id, vector))
    return result
