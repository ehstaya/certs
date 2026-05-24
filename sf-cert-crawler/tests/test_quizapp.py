"""Tests for the quiz-app client (crawler/quizapp.py) — no real HTTP.

The import API is exam-scoped: a single envelope {slug,name,...,questions:[...]}
is POSTed and the response carries an `exam` key.
"""

from __future__ import annotations

import httpx
import pytest

from crawler.quizapp import QuizAppClient, QuizAppError

ENVELOPE = {
    "slug": "salesforce-admin",
    "name": "Salesforce Administrator (CRT-101)",
    "questions": [{"type": "SINGLE", "text": "What is OWD?", "choices": [{"label": "A", "text": "x", "correct": True}]}],
}


def _client(handler, *, password="pw") -> QuizAppClient:
    return QuizAppClient(
        base_url="http://quiz.test", email="admin@local", password=password,
        transport=httpx.MockTransport(handler),
    )


def test_missing_credentials_raises() -> None:
    with pytest.raises(QuizAppError):
        QuizAppClient(base_url="http://quiz.test", email=None, password=None)


def test_login_then_import_succeeds() -> None:
    seen: list[tuple[str, str]] = []
    body_seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path))
        if request.url.path == "/login":
            return httpx.Response(302, headers={"location": "/"})
        if request.url.path == "/admin/questions/import":
            assert request.headers["content-type"].startswith("application/json")
            import json as _json
            body_seen.update(_json.loads(request.content))
            return httpx.Response(200, json={"exam": "salesforce-admin", "imported": 3, "skipped": 1, "skippedTexts": ["dup"]})
        return httpx.Response(404)

    with _client(handler) as client:
        client.login()
        result = client.import_exam(ENVELOPE)
    assert result.exam == "salesforce-admin"
    assert result.imported == 3 and result.skipped == 1 and result.skipped_texts == ["dup"]
    assert ("POST", "/login") in seen and ("POST", "/admin/questions/import") in seen
    # the envelope (not a bare list) was sent
    assert body_seen["slug"] == "salesforce-admin" and isinstance(body_seen["questions"], list)


def test_import_auto_logs_in_and_defaults_exam_to_slug() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/login":
            return httpx.Response(302, headers={"location": "/"})
        if request.url.path == "/admin/questions/import":
            return httpx.Response(200, json={"imported": 1, "skipped": 0})  # no "exam" key
        return httpx.Response(404)

    with _client(handler) as client:
        result = client.import_exam(ENVELOPE)
    assert result.imported == 1
    assert result.exam == "salesforce-admin"  # falls back to envelope slug


def test_empty_questions_short_circuits() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path == "/login":
            return httpx.Response(302, headers={"location": "/"})
        return httpx.Response(500)  # should never be hit for /import

    with _client(handler) as client:
        client.login()
        result = client.import_exam({"slug": "salesforce-admin", "questions": []})
    assert result.imported == 0 and result.exam == "salesforce-admin"
    assert "/admin/questions/import" not in calls


def test_login_failure_raises() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "/login?error"})

    with _client(handler, password="wrong") as client:
        with pytest.raises(QuizAppError, match="login failed"):
            client.login()


def test_force_change_password_raises() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "/change-password"})

    with _client(handler) as client:
        with pytest.raises(QuizAppError, match="change its password"):
            client.login()


def test_import_redirect_means_not_admin() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/login":
            return httpx.Response(302, headers={"location": "/"})
        return httpx.Response(302, headers={"location": "/login"})  # import bounced

    with _client(handler) as client:
        client.login()
        with pytest.raises(QuizAppError, match="redirected"):
            client.import_exam(ENVELOPE)
