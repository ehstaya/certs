"""Tests for the cert -> exam envelope mapping (crawler/exams.py)."""

from __future__ import annotations

import dataclasses

import pytest

from crawler import exams


def test_admin_is_mapped_and_server_seeded() -> None:
    meta = exams.exam_meta("admin")
    assert meta.slug == "salesforce-admin"
    assert meta.name == "Salesforce Administrator (CRT-101)"
    assert meta.server_seeded is True


def test_unknown_cert_raises_with_clean_message() -> None:
    with pytest.raises(exams.UnknownCert) as ei:
        exams.exam_meta("does-not-exist")
    msg = str(ei.value)
    assert "does-not-exist" in msg and "admin" in msg
    assert not msg.startswith('"')  # KeyError quoting suppressed


def test_envelope_for_seeded_exam_sends_only_slug_name_questions() -> None:
    qs = [{"type": "SINGLE", "text": "Q?", "choices": []}]
    env = exams.build_envelope("admin", qs)
    assert env == {
        "slug": "salesforce-admin",
        "name": "Salesforce Administrator (CRT-101)",
        "questions": qs,
    }
    # don't fight the seed over these:
    for k in ("questionsPerSession", "durationMinutes", "sortOrder", "active", "description"):
        assert k not in env


def test_envelope_for_non_seeded_exam_sends_full_metadata(monkeypatch) -> None:
    custom = exams.ExamMeta(
        slug="aws-saa", name="AWS SAA", description="d",
        questions_per_session=65, duration_minutes=130, sort_order=20,
        active=True, server_seeded=False,
    )
    monkeypatch.setitem(exams.CERT_EXAMS, "aws", custom)
    env = exams.build_envelope("aws", [{"text": "x"}])
    assert env["slug"] == "aws-saa"
    assert env["questionsPerSession"] == 65
    assert env["durationMinutes"] == 130
    assert env["sortOrder"] == 20
    assert env["active"] is True
    assert env["description"] == "d"
    assert env["questions"] == [{"text": "x"}]


def test_exam_meta_is_immutable() -> None:
    meta = exams.exam_meta("admin")
    with pytest.raises(dataclasses.FrozenInstanceError):
        meta.slug = "tampered"  # type: ignore[misc]
