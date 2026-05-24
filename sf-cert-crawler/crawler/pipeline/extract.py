from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone

from ..llm import LLM, SONNET, parse_json_array
from ..logging_setup import get_logger
from ..models import Question

log = get_logger(__name__)

_ALLOWED_DIFFICULTY = {"easy", "medium", "hard"}

_EXTRACTOR_SYSTEM = """You extract Salesforce certification study questions from web-page text, for a learner's personal practice database.

Return ONLY a JSON array (no prose, no markdown fences). Each element:
{
  "question_text": "<the question prompt, verbatim or lightly cleaned of HTML noise>",
  "options": ["<choice>", ...] or null,           // answer choices if the source lists them; null for open-ended / scenario prompts
  "correct_answer": "<the exact choice text>" or null,  // ONLY if the source explicitly states the correct answer; otherwise null — NEVER guess
  "explanation": "<the source's explanation>" or null,  // only if the source provides one
  "topic": "<broad area>" or null,                // best short guess, e.g. "Security", "Data Management", "Automation", "Sales", "Service", "Reports & Dashboards", "Configuration", "Deployment"; null if unsure
  "difficulty": "easy" | "medium" | "hard" | null,     // only if the source labels it; otherwise null
  "confidence": <number between 0.0 and 1.0>      // how confident you are this is a real, well-formed practice question
}

Rules:
- Do NOT invent answers or explanations. If the source does not state the correct answer, "correct_answer" MUST be null.
- "correct_answer", when not null, must be one of the strings in "options" (when options is non-null).
- Only include genuine practice/study questions. Skip article prose, navigation, ads, comments, calls-to-action, author bios.
- If there are no questions at all, return [].
- Output ONLY the JSON array."""


def _truncate(text: str, max_chars: int) -> str:
    return text if len(text) <= max_chars else text[:max_chars] + "\n…[truncated]"


def _clean_str(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    collapsed = re.sub(r"\s+", " ", value).strip()
    return collapsed or None


def _clean_str_list(value: object) -> list[str] | None:
    if not isinstance(value, list):
        return None
    out = [s for s in (_clean_str(v) for v in value) if s]
    return out or None


def _clamp01(value: object, *, default: float) -> float:
    try:
        f = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, f))


def _question_id(question_text: str) -> str:
    return hashlib.sha256(re.sub(r"\s+", " ", question_text).strip().lower().encode("utf-8")).hexdigest()[:16]


def extract_questions(
    llm: LLM,
    *,
    title: str,
    text: str,
    source_url: str,
    source_type: str,
    source_license_note: str,
    cert: str,
    max_chars: int = 24000,
    max_output_tokens: int = 4000,
) -> list[Question]:
    user = f"TITLE: {title}\n\nTEXT:\n{_truncate(text, max_chars)}"
    raw = llm.complete(model=SONNET, system=_EXTRACTOR_SYSTEM, user=user, max_tokens=max_output_tokens)
    return _items_to_questions(
        raw, source_url=source_url, source_type=source_type,
        source_license_note=source_license_note, cert=cert,
    )


def extract_questions_from_image(
    llm: LLM,
    *,
    title: str,
    image_bytes: bytes,
    image_mime: str,
    source_url: str,
    source_type: str,
    source_license_note: str,
    cert: str,
    max_output_tokens: int = 4000,
) -> list[Question]:
    """Vision-extract practice questions from a screenshot / image (Claude Sonnet vision).

    Same JSON contract as the text extractor; the model is told never to invent
    correct answers, and the post-parse step drops any answer that isn't grounded
    in the listed options.
    """
    user_text = (
        f"TITLE: {title}\n\n"
        "(See attached image — extract every practice question visible. "
        "If the image isn't a practice question, return [].)"
    )
    raw = llm.complete_with_image(
        model=SONNET, system=_EXTRACTOR_SYSTEM, user_text=user_text,
        image_bytes=image_bytes, image_mime=image_mime, max_tokens=max_output_tokens,
    )
    return _items_to_questions(
        raw, source_url=source_url, source_type=source_type,
        source_license_note=source_license_note, cert=cert,
    )


def _items_to_questions(
    raw: str,
    *,
    source_url: str,
    source_type: str,
    source_license_note: str,
    cert: str,
) -> list[Question]:
    """Parse a JSON array reply and assemble Question objects, with the
    never-invent-an-answer rule enforced post-parse."""
    items = parse_json_array(raw)
    if items is None:
        log.warning("extract: could not parse a JSON array from the reply (%d chars); no questions", len(raw))
        return []

    out: list[Question] = []
    seen: set[str] = set()
    now = datetime.now(timezone.utc)
    for item in items:
        if not isinstance(item, dict):
            continue
        question_text = _clean_str(item.get("question_text"))
        if not question_text:
            continue
        options = _clean_str_list(item.get("options"))
        correct = _clean_str(item.get("correct_answer"))
        if correct and options and correct not in options:
            # The model named an answer that isn't an exact option — accept only a
            # whitespace/case-insensitive match, otherwise drop it (don't guess).
            correct = next((o for o in options if o.strip().lower() == correct.strip().lower()), None)
        explanation = _clean_str(item.get("explanation"))
        difficulty_raw = item.get("difficulty")
        difficulty = difficulty_raw if difficulty_raw in _ALLOWED_DIFFICULTY else None
        topic = _clean_str(item.get("topic"))
        confidence = _clamp01(item.get("confidence"), default=0.5)

        qid = _question_id(question_text)
        if qid in seen:
            continue
        seen.add(qid)
        out.append(
            Question(
                id=qid,
                cert=cert,
                topic=topic,
                question_text=question_text,
                options=options,
                correct_answer=correct,
                explanation=explanation,
                difficulty=difficulty,
                source_url=source_url,
                source_type=source_type,
                source_license_note=source_license_note,
                extracted_at=now,
                confidence=confidence,
            )
        )
    return out
