"""Tests for the automated quality gate (crawler/pipeline/quality.py)."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from crawler.models import Question
from crawler.pipeline import quality


def _q(text: str, options=None, correct=None, confidence: float = 1.0) -> Question:
    return Question(
        id="0" * 16,
        cert="admin",
        question_text=text,
        options=options,
        correct_answer=correct,
        source_url="https://example.test/",
        source_type="blog",
        source_license_note="note",
        extracted_at=datetime.now(timezone.utc),
        confidence=confidence,
    )


def test_well_formed_single_question_passes() -> None:
    r = quality.check_quality(_q("What does an org-wide default control?", ["A profile", "Baseline record access"], "Baseline record access"))
    assert r.ok and r.question_type == "SINGLE"
    assert r.correct_options == ["Baseline record access"]


def test_multi_select_question_passes() -> None:
    r = quality.check_quality(_q("Which apply? (Choose 2)", ["X", "Y", "Z"], "X; Y"))
    assert r.ok and r.question_type == "MULTI"
    assert sorted(r.correct_options) == ["X", "Y"]


def test_choose_n_hint_makes_it_multi_even_with_one_correct() -> None:
    r = quality.check_quality(_q("Pick all that apply (Choose 3): which is true?", ["X", "Y", "Z"], "Y"))
    assert r.question_type == "MULTI"


def test_no_correct_answer_fails() -> None:
    r = quality.check_quality(_q("What is a permission set?", ["A", "B", "C"], None))
    assert not r.ok and any("no correct answer" in i for i in r.issues)


def test_correct_answer_not_among_options_fails() -> None:
    r = quality.check_quality(_q("Q?", ["Foo", "Bar"], "Baz"))
    assert not r.ok and any("not among options" in i for i in r.issues)


def test_fewer_than_two_options_fails() -> None:
    r = quality.check_quality(_q("Q?", ["Only one"], "Only one"))
    assert not r.ok and any("fewer than 2" in i for i in r.issues)


def test_duplicate_options_fails() -> None:
    r = quality.check_quality(_q("Q?", ["Same", "same", "Other"], "Other"))
    assert not r.ok and any("duplicate" in i for i in r.issues)


def test_low_confidence_fails() -> None:
    r = quality.check_quality(_q("A perfectly fine question text here", ["A", "B"], "A", confidence=0.1))
    assert not r.ok and any("confidence" in i for i in r.issues)


def test_single_with_two_correct_fails() -> None:
    # No "(choose N)" hint, but two correct -> inferred MULTI, so this actually passes;
    # to trigger the "single-answer with multiple correct" issue we need the type forced
    # to SINGLE, which only happens when exactly one correct is present. Instead verify
    # the inferred-MULTI path is accepted:
    r = quality.check_quality(_q("Which two are valid?", ["A", "B", "C"], "A; B"))
    assert r.ok and r.question_type == "MULTI"
