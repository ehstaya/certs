from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from .config import settings
from .logging_setup import get_logger

log = get_logger(__name__)


class QuizAppError(RuntimeError):
    pass


@dataclass
class ImportResult:
    exam: str
    imported: int
    skipped: int
    skipped_texts: list[str]


class QuizAppClient:
    """Client for the Spring "Salesforce Admin Practice playground" app.

    Logs in via the form-login flow (CSRF is disabled in that app), then POSTs
    questions to ``/admin/questions/import`` where they land as ``PENDING`` for
    a human to approve in the admin UI.

    Important: use the admin account's *current* password. The default
    ``ChangeMe123!`` triggers the app's force-change-password filter, which would
    redirect the import request to ``/change-password``.
    """

    def __init__(
        self,
        *,
        base_url: str | None = None,
        email: str | None = None,
        password: str | None = None,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.base_url = (base_url or settings.quiz_app_url).rstrip("/")
        self.email = email or settings.quiz_app_admin_email
        self.password = password or settings.quiz_app_admin_password
        if not self.email or not self.password:
            raise QuizAppError(
                "quiz-app admin credentials not configured — set QUIZ_APP_ADMIN_EMAIL and "
                "QUIZ_APP_ADMIN_PASSWORD in .env"
            )
        self._transport = transport  # for tests (httpx.MockTransport)
        self._client: httpx.Client | None = None
        self._logged_in = False

    def __enter__(self) -> "QuizAppClient":
        self._client = httpx.Client(
            base_url=self.base_url,
            timeout=settings.request_timeout_s,
            follow_redirects=False,  # we want to see redirects explicitly
            headers={"User-Agent": settings.user_agent},
            transport=self._transport,
        )
        return self

    def __exit__(self, *exc: object) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None

    @property
    def _http(self) -> httpx.Client:
        if self._client is None:
            raise QuizAppError("QuizAppClient must be used as a context manager")
        return self._client

    def login(self) -> None:
        resp = self._http.post(
            "/login",
            data={"email": self.email, "password": self.password},
        )
        # Spring form login -> 302. Success redirects to "/"; failure to "/login?error".
        location = resp.headers.get("location", "")
        if resp.status_code not in (301, 302, 303) or "login?error" in location:
            raise QuizAppError(f"login failed (HTTP {resp.status_code}, location={location!r}) — check QUIZ_APP_ADMIN_* credentials")
        if "change-password" in location:
            raise QuizAppError(
                "login succeeded but the admin account must change its password first — "
                "change it in the quiz app, then set QUIZ_APP_ADMIN_PASSWORD to the new value"
            )
        self._logged_in = True
        log.info("logged in to quiz app at %s as %s", self.base_url, self.email)

    def import_exam(self, envelope: dict[str, Any]) -> ImportResult:
        """POST one exam envelope ({slug, name, [metadata], questions:[...]}).

        The exam is upserted by ``slug`` and its questions are created PENDING.
        """
        if not self._logged_in:
            self.login()
        slug = str(envelope.get("slug", ""))
        if not envelope.get("questions"):
            return ImportResult(slug, 0, 0, [])
        resp = self._http.post("/admin/questions/import", json=envelope)
        if resp.status_code in (301, 302, 303):
            location = resp.headers.get("location", "")
            raise QuizAppError(
                f"import redirected to {location!r} — likely not authenticated as ADMIN "
                "(or the force-change-password filter intercepted it)"
            )
        if resp.status_code >= 400:
            raise QuizAppError(f"import failed: HTTP {resp.status_code} {resp.text[:300]}")
        data = resp.json()
        return ImportResult(
            exam=str(data.get("exam", slug)),
            imported=int(data.get("imported", 0)),
            skipped=int(data.get("skipped", 0)),
            skipped_texts=list(data.get("skippedTexts", []) or []),
        )
