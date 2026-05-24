from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Protocol

from ..config import settings


class _QuestionLike(Protocol):
    """Structural type satisfied by both `crawler.models.Question` and the
    SQLAlchemy `QuestionRow`."""

    question_text: str
    options: list[str] | None
    correct_answer: str | None
    confidence: float | None


# A question is only eligible to push into the quiz app if it's answerable and
# well-formed: it must have a definite correct answer (the quiz app grades
# against `is_correct` choices) and at least two distinct options. This gate
# runs *before* the human approval step — it filters out junk so a person only
# reviews plausible questions.

_MULTI_HINT_RE = re.compile(r"\(\s*choose\s+\d+\s*\)|select\s+all\s+that\s+apply|choose\s+(two|three|four)\b", re.IGNORECASE)
_MIN_QUESTION_CHARS = 15
_MAX_OPTIONS = 8


@dataclass
class QualityResult:
    ok: bool
    question_type: str  # "SINGLE" | "MULTI"
    correct_options: list[str] = field(default_factory=list)  # subset of question.options
    issues: list[str] = field(default_factory=list)


def _split_correct(answer: str) -> list[str]:
    # extract.py joins multi-select answers with "; "
    return [part.strip() for part in answer.split(";") if part.strip()]


def infer_type(question: _QuestionLike, correct_options: list[str]) -> str:
    if len(correct_options) > 1:
        return "MULTI"
    if _MULTI_HINT_RE.search(question.question_text):
        return "MULTI"
    return "SINGLE"


def check_quality(question: _QuestionLike) -> QualityResult:
    issues: list[str] = []

    text = (question.question_text or "").strip()
    if len(text) < _MIN_QUESTION_CHARS:
        issues.append("question text too short")

    options = [o.strip() for o in (question.options or []) if o and o.strip()]
    if len(options) < 2:
        issues.append("fewer than 2 answer options")
    if len(options) > _MAX_OPTIONS:
        issues.append(f"more than {_MAX_OPTIONS} answer options")
    if len(options) != len(set(o.lower() for o in options)):
        issues.append("duplicate answer options")

    correct_options: list[str] = []
    if not question.correct_answer:
        issues.append("no correct answer stated (quiz app needs one)")
    else:
        wanted = _split_correct(question.correct_answer)
        by_lower = {o.lower(): o for o in options}
        for w in wanted:
            match = by_lower.get(w.lower())
            if match is None:
                issues.append(f"correct answer not among options: {w!r}")
            elif match not in correct_options:
                correct_options.append(match)
        if not correct_options and not any("correct answer not among options" in i for i in issues):
            issues.append("correct answer resolved to no option")

    qtype = infer_type(question, correct_options)
    if qtype == "SINGLE" and len(correct_options) > 1:
        issues.append("single-answer question has multiple correct options")

    conf = question.confidence if question.confidence is not None else 0.0
    if conf < settings.quality_min_confidence:
        issues.append(f"extractor confidence {conf:.2f} below {settings.quality_min_confidence:.2f}")

    return QualityResult(ok=not issues, question_type=qtype, correct_options=correct_options, issues=issues)
