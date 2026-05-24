"""Tests for the Claude-backed pipeline (classify / dump-check / extract) and the
cost meter. Uses a scripted fake Anthropic client — no network, no API key."""

from __future__ import annotations

import json

import pytest

from crawler.llm import HAIKU, SONNET, BudgetExceeded, CostMeter, LLM
from crawler.pipeline import classify, extract


# --- fake Anthropic SDK ---------------------------------------------------------


class _FakeUsage:
    def __init__(self, input_tokens=1000, output_tokens=100, cache_creation=0, cache_read=0):
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.cache_creation_input_tokens = cache_creation
        self.cache_read_input_tokens = cache_read


class _FakeBlock:
    def __init__(self, text: str):
        self.type = "text"
        self.text = text


class _FakeResp:
    def __init__(self, text: str, usage: _FakeUsage | None = None):
        self.content = [_FakeBlock(text)]
        self.usage = usage or _FakeUsage()


class _FakeMessages:
    def __init__(self, scripted):
        self._scripted = list(scripted)
        self.calls: list[dict] = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        item = self._scripted.pop(0)
        return item if isinstance(item, _FakeResp) else _FakeResp(item)


class FakeAnthropic:
    def __init__(self, scripted):
        self.messages = _FakeMessages(scripted)


def _llm(scripted, **meter_kwargs):
    meter = CostMeter(**meter_kwargs)
    return LLM(meter=meter, client=FakeAnthropic(scripted)), meter


# --- classify -------------------------------------------------------------------


def test_has_study_questions_parses_json() -> None:
    llm, meter = _llm(['{"has_questions": true, "approx_count": 12, "reason": "MCQs present"}'])
    res = classify.has_study_questions(llm, title="Practice Exam", text="Q1. ...")
    assert res.has_questions is True
    assert res.approx_count == 12
    assert meter.calls == 1


def test_has_study_questions_handles_fenced_json() -> None:
    llm, _ = _llm(['```json\n{"has_questions": false, "approx_count": 0, "reason": "opinion piece"}\n```'])
    assert classify.has_study_questions(llm, title="Opinion", text="prose").has_questions is False


def test_has_study_questions_unparseable_defaults_false() -> None:
    llm, _ = _llm(["yes, there seem to be some questions here"])
    assert classify.has_study_questions(llm, title="x", text="y").has_questions is False


def test_dump_check_recognizes_words() -> None:
    for reply, expected in [
        ("dump", "dump"),
        ("This looks clean to me.", "clean"),
        ("suspicious", "suspicious"),
        ("¯\\_(ツ)_/¯", "suspicious"),  # unrecognized -> conservative default
    ]:
        llm, _ = _llm([reply])
        assert classify.dump_check(llm, title="t", text="b").decision == expected


# --- extract --------------------------------------------------------------------


def test_extract_builds_questions_without_guessed_answers() -> None:
    payload = json.dumps(
        [
            {
                "question_text": "What does an organization-wide default control?",
                "options": ["A user's profile", "The baseline level of record access"],
                "correct_answer": "The baseline level of record access",
                "explanation": "OWD sets the baseline.",
                "topic": "Security",
                "difficulty": "medium",
                "confidence": 0.9,
            },
            {
                "question_text": "Scenario: pick an automation approach.",
                "options": None,
                "correct_answer": None,
                "explanation": None,
                "topic": None,
                "difficulty": "impossible",  # not allowed -> None
                "confidence": 2.5,  # out of range -> clamped
            },
            {  # duplicate of the first question (normalized text) -> dropped
                "question_text": "  what does an   organization-wide default control?  ",
                "options": ["A", "B"],
                "confidence": 0.3,
            },
            {"not_a_question": True},  # junk -> skipped
            "definitely not a dict",  # junk -> skipped
        ]
    )
    llm, _ = _llm([payload])
    qs = extract.extract_questions(
        llm, title="t", text="...", source_url="https://x/y/", source_type="blog",
        source_license_note="note", cert="admin",
    )
    assert len(qs) == 2

    q1, q2 = qs
    assert q1.correct_answer == "The baseline level of record access"
    assert q1.topic == "Security" and q1.difficulty == "medium"
    assert q1.confidence == 0.9
    assert len(q1.id) == 16 and all(c in "0123456789abcdef" for c in q1.id)
    assert q1.source_url == "https://x/y/" and q1.cert == "admin"

    assert q2.options is None and q2.correct_answer is None and q2.difficulty is None
    assert 0.0 <= q2.confidence <= 1.0


def test_extract_drops_ungrounded_correct_answer() -> None:
    payload = json.dumps([{"question_text": "Q?", "options": ["Foo", "Bar"], "correct_answer": "Baz", "confidence": 0.8}])
    llm, _ = _llm([payload])
    qs = extract.extract_questions(
        llm, title="t", text="...", source_url="u", source_type="blog", source_license_note="n", cert="admin"
    )
    assert len(qs) == 1 and qs[0].correct_answer is None and qs[0].options == ["Foo", "Bar"]


def test_extract_accepts_case_insensitive_option_match() -> None:
    payload = json.dumps([{"question_text": "Q?", "options": ["Flow", "Apex trigger"], "correct_answer": "flow", "confidence": 1.0}])
    llm, _ = _llm([payload])
    qs = extract.extract_questions(
        llm, title="t", text="...", source_url="u", source_type="blog", source_license_note="n", cert="admin"
    )
    assert qs[0].correct_answer == "Flow"


def test_extract_empty_array_and_garbage() -> None:
    llm, _ = _llm(["[]"])
    assert extract.extract_questions(llm, title="t", text="x", source_url="u", source_type="blog", source_license_note="n", cert="admin") == []
    llm, _ = _llm(["I could not find any questions in this text."])
    assert extract.extract_questions(llm, title="t", text="x", source_url="u", source_type="blog", source_license_note="n", cert="admin") == []


# --- cost meter & budget guard --------------------------------------------------


def test_cost_meter_accounting() -> None:
    meter = CostMeter(budget_usd=1.0)
    meter.record(HAIKU, input_side_tokens=1_000_000, output_tokens=0)  # $1.00 input
    assert meter.spent_usd == pytest.approx(1.00, rel=1e-6)
    meter.record(SONNET, input_side_tokens=0, output_tokens=1_000_000)  # +$15.00 output
    assert meter.spent_usd == pytest.approx(16.00, rel=1e-6)
    assert meter.usage_for(HAIKU) == (1_000_000, 0)


def test_budget_guard_blocks_then_can_override() -> None:
    meter = CostMeter(budget_usd=0.001)
    meter.record(HAIKU, input_side_tokens=10_000, output_tokens=0)  # ~$0.01 spent, > $0.001
    with pytest.raises(BudgetExceeded):
        meter.guard()
    meter.allow_overspend = True
    meter.guard()  # no raise


def test_llm_complete_records_usage_including_cache_tokens_and_caches_system() -> None:
    llm, meter = _llm([_FakeResp("ok", _FakeUsage(input_tokens=100, output_tokens=20, cache_creation=500, cache_read=400))])
    out = llm.complete(model=HAIKU, system="system prompt", user="user msg", max_tokens=50)
    assert out == "ok"
    assert meter.usage_for(HAIKU) == (1000, 20)  # 100 + 500 + 400 input-side
    sent = llm._client.messages.calls[0]
    assert sent["model"] == HAIKU
    assert sent["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert sent["messages"] == [{"role": "user", "content": "user msg"}]


def test_llm_complete_guards_budget_before_calling() -> None:
    # Budget already blown -> complete() must raise before issuing the request.
    llm, meter = _llm(["should not be reached"], budget_usd=0.0)
    meter.record(HAIKU, input_side_tokens=1, output_tokens=1)
    with pytest.raises(BudgetExceeded):
        llm.complete(model=HAIKU, system="s", user="u", max_tokens=10)
    assert llm._client.messages.calls == []  # no request was made


def test_llm_complete_with_image_sends_image_block_and_records_usage() -> None:
    import base64

    from crawler.llm import SONNET

    llm, meter = _llm([_FakeResp("[]", _FakeUsage(input_tokens=1500, output_tokens=8))])
    img = b"\x89PNG\r\n\x1a\nfake-bytes"
    out = llm.complete_with_image(
        model=SONNET, system="sys", user_text="extract questions from the image",
        image_bytes=img, image_mime="image/png", max_tokens=400,
    )
    assert out == "[]"
    # input-side tokens recorded (includes image input)
    assert meter.usage_for(SONNET) == (1500, 8)
    # the request contains a properly-structured image content block + text block
    sent = llm._client.messages.calls[0]
    assert sent["model"] == SONNET
    assert sent["system"][0]["cache_control"] == {"type": "ephemeral"}
    content = sent["messages"][0]["content"]
    assert content[0]["type"] == "image"
    assert content[0]["source"]["type"] == "base64"
    assert content[0]["source"]["media_type"] == "image/png"
    assert content[0]["source"]["data"] == base64.standard_b64encode(img).decode("ascii")
    assert content[1] == {"type": "text", "text": "extract questions from the image"}


def test_extract_questions_from_image_builds_question_objects() -> None:
    import json

    from crawler.llm import SONNET
    from crawler.pipeline import extract

    payload = json.dumps([
        {
            "question_text": "Which permission lets the admin log in as any user?",
            "options": ["Modify All Data", "Administrators Can Log In as Any User", "Delegated Administration"],
            "correct_answer": "Administrators Can Log In as Any User",
            "explanation": "Login Access Policies setting.",
            "topic": "Security",
            "difficulty": "easy",
            "confidence": 0.95,
        }
    ])
    llm, _ = _llm([_FakeResp(payload, _FakeUsage(input_tokens=1200, output_tokens=80))])
    qs = extract.extract_questions_from_image(
        llm, title="Practice Q Screenshot",
        image_bytes=b"\x89PNGfake", image_mime="image/png",
        source_url="file:///uploads/screenshot.png",
        source_type="other", source_license_note="local",
        cert="admin",
    )
    assert len(qs) == 1
    q = qs[0]
    assert q.question_text.startswith("Which permission")
    assert q.correct_answer == "Administrators Can Log In as Any User"
    assert q.topic == "Security" and q.difficulty == "easy"
    # confirm the SDK was called with a Sonnet image-bearing message
    sent = llm._client.messages.calls[0]
    assert sent["model"] == SONNET
    assert sent["messages"][0]["content"][0]["type"] == "image"
