from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from ..llm import HAIKU, LLM, parse_json_object
from ..logging_setup import get_logger

log = get_logger(__name__)

# Stable system prompts (cached via cache_control in LLM.complete).
_HAS_QUESTIONS_SYSTEM = """You classify web-page text for a personal Salesforce certification study tool.

Decide whether the text CONTAINS one or more Salesforce certification practice/study QUESTIONS that a learner could quiz themselves with — an explicit question prompt (ideally with answer options), or a clear "what would you do" scenario prompt. Do NOT count: general discussion, news, opinion pieces, study *tips*, exam-experience write-ups, course/product advertisements, navigation or boilerplate text.

Reply with ONLY a JSON object, no prose:
{"has_questions": true|false, "approx_count": <integer>, "reason": "<one short sentence>"}"""

_DUMP_CHECK_SYSTEM = """You are a content-integrity check for a personal Salesforce study tool.

Given web-page text that appears to contain certification questions, decide whether it looks like LEAKED or PAID real-exam content versus legitimate user-generated study material (practice questions written for study, Trailhead-style "check your understanding", forum discussion, blog practice sets). Anything that claims to contain "real exam questions", "actual exam questions", "exam dumps", "verified answers", or that reads like a transcription of a live certification exam is a dump. Material explicitly written and published as practice — even if hard or extensive — is clean.

Reply with ONLY one word: clean | suspicious | dump"""

DumpDecision = Literal["clean", "suspicious", "dump"]


@dataclass
class QuestionCheck:
    has_questions: bool
    approx_count: int
    reason: str


@dataclass
class DumpCheck:
    decision: DumpDecision
    raw: str


def _truncate(text: str, max_chars: int) -> str:
    return text if len(text) <= max_chars else text[:max_chars] + "\n…[truncated]"


def _safe_int(value: object) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0


def has_study_questions(llm: LLM, *, title: str, text: str, max_chars: int = 14000) -> QuestionCheck:
    user = f"TITLE: {title}\n\nTEXT:\n{_truncate(text, max_chars)}"
    raw = llm.complete(model=HAIKU, system=_HAS_QUESTIONS_SYSTEM, user=user, max_tokens=200)
    obj = parse_json_object(raw)
    if obj is None:
        log.warning("classify: could not parse reply %r; assuming no questions", raw[:200])
        return QuestionCheck(False, 0, "unparseable classifier reply")
    return QuestionCheck(
        has_questions=bool(obj.get("has_questions", False)),
        approx_count=_safe_int(obj.get("approx_count")),
        reason=str(obj.get("reason") or ""),
    )


def dump_check(llm: LLM, *, title: str, text: str, max_chars: int = 14000) -> DumpCheck:
    user = f"TITLE: {title}\n\nTEXT:\n{_truncate(text, max_chars)}"
    raw = llm.complete(model=HAIKU, system=_DUMP_CHECK_SYSTEM, user=user, max_tokens=20)
    lowered = raw.strip().lower()
    for decision in ("dump", "suspicious", "clean"):
        if decision in lowered:
            return DumpCheck(decision, raw)  # type: ignore[arg-type]
    log.warning("dump_check: unrecognized reply %r; treating as suspicious", raw[:120])
    return DumpCheck("suspicious", raw)
