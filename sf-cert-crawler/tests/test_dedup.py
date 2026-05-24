"""Tests for semantic dedup. Uses a tiny deterministic fake embedder — no
sentence-transformers / torch needed for the test."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from crawler.models import Question
from crawler.pipeline import dedup


def _q(text: str, qid: str) -> Question:
    return Question(
        id=qid,
        cert="admin",
        question_text=text,
        source_url="https://example.test/",
        source_type="blog",
        source_license_note="note",
        extracted_at=datetime.now(timezone.utc),
        confidence=1.0,
    )


def _fake_embedder(mapping: dict[str, list[float]]) -> dedup.Embedder:
    # Returns a fixed vector per known text; unknown texts get an all-zero vector
    # of the same dimensionality (cosine 0 against everything -> always unique).
    dim = len(next(iter(mapping.values())))

    def embed(texts: list[str]) -> list[list[float]]:
        return [mapping.get(t, [0.0] * dim) for t in texts]

    return embed


def test_pack_unpack_roundtrip() -> None:
    vec = [0.1, -0.25, 0.5, 1.0, -1.0]
    blob = dedup.pack_embedding(vec)
    back = dedup.unpack_embedding(blob)
    assert back is not None and len(back) == len(vec)
    assert all(abs(a - b) < 1e-6 for a, b in zip(vec, back))
    assert dedup.unpack_embedding(None) is None
    assert dedup.unpack_embedding(b"") is None


def test_cosine_similarity_basic() -> None:
    assert dedup.cosine_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert dedup.cosine_similarity([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert dedup.cosine_similarity([1.0, 0.0], [-1.0, 0.0]) == pytest.approx(-1.0)
    assert dedup.cosine_similarity([], [1.0]) == 0.0  # mismatched / empty -> 0


def test_semantic_dedup_flags_near_duplicate_of_existing() -> None:
    embedder = _fake_embedder({
        "What does an org-wide default control?": [1.0, 0.0, 0.0],
        "What do organization-wide defaults control?": [0.99, 0.14, 0.0],  # ~0.99 similar
        "What is a permission set?": [0.0, 0.0, 1.0],
    })
    existing = [("existing01", [1.0, 0.0, 0.0])]  # already-stored "org-wide default" question
    candidates = [
        _q("What do organization-wide defaults control?", "cand01"),  # near-dup of existing
        _q("What is a permission set?", "cand02"),  # unique
    ]
    res = dedup.semantic_dedup(candidates, existing=existing, embedder=embedder, threshold=0.92)
    assert [q.id for q, _ in res.unique] == ["cand02"]
    assert len(res.near_duplicates) == 1
    nd = res.near_duplicates[0]
    assert nd.question.id == "cand01" and nd.matched_id == "existing01" and nd.similarity >= 0.92


def test_semantic_dedup_flags_within_batch() -> None:
    embedder = _fake_embedder({
        "Question A phrasing one": [1.0, 0.0],
        "Question A phrasing two": [1.0, 0.0],  # identical vector -> sim 1.0
        "Totally different question": [0.0, 1.0],
    })
    candidates = [
        _q("Question A phrasing one", "a1"),
        _q("Question A phrasing two", "a2"),  # near-dup of a1 within the same batch
        _q("Totally different question", "b1"),
    ]
    res = dedup.semantic_dedup(candidates, existing=[], embedder=embedder, threshold=0.92)
    assert {q.id for q, _ in res.unique} == {"a1", "b1"}
    assert [nd.question.id for nd in res.near_duplicates] == ["a2"]


def test_semantic_dedup_keeps_distinct_questions() -> None:
    embedder = _fake_embedder({
        "Q one": [1.0, 0.0, 0.0],
        "Q two": [0.0, 1.0, 0.0],
        "Q three": [0.0, 0.0, 1.0],
    })
    candidates = [_q("Q one", "1"), _q("Q two", "2"), _q("Q three", "3")]
    res = dedup.semantic_dedup(candidates, existing=[], embedder=embedder, threshold=0.92)
    assert len(res.unique) == 3 and not res.near_duplicates


def test_semantic_dedup_empty_input() -> None:
    res = dedup.semantic_dedup([], existing=[], embedder=_fake_embedder({"x": [1.0]}), threshold=0.9)
    assert not res.unique and not res.near_duplicates
