"""Cert key -> quiz-app exam envelope metadata.

The quiz app's import API (`POST /admin/questions/import`) is exam-scoped: the
question array must be wrapped in an "exam envelope" that names the target exam
by `slug`. The server upserts the exam by slug; for exams it also seeds from
`seed/*.json` it resets metadata on every restart, so for those we send only
`slug` + `name` + `questions` and don't fight the seed over
session/duration/sort. For a brand-new (non-seeded) exam the crawler is
authoritative and sends the full metadata.

Extend `CERT_EXAMS` with new cert keys (platform-app-builder, aws-saa, togaf, …).
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ExamMeta:
    slug: str
    name: str
    description: str
    questions_per_session: int
    duration_minutes: int
    sort_order: int
    active: bool = True
    # True when the quiz app also seeds this exam from seed/*.json and resets
    # its metadata on restart. Then we only send slug/name and let the seed own
    # questionsPerSession/durationMinutes/sortOrder (per CRAWLER_API_CHANGE.md).
    server_seeded: bool = False


CERT_EXAMS: dict[str, ExamMeta] = {
    "admin": ExamMeta(
        slug="salesforce-admin",
        name="Salesforce Administrator (CRT-101)",
        description="Core admin exam: configuration, security, automation, data, reporting.",
        questions_per_session=60,
        duration_minutes=105,
        sort_order=10,
        server_seeded=True,
    ),
    # Future: "platform-app-builder", "aws-saa", "togaf" — add ExamMeta entries.
}


class UnknownCert(KeyError):
    """No exam mapping exists for the given cert key."""

    def __str__(self) -> str:  # KeyError repr is quote-wrapped; keep our message clean
        return self.args[0] if self.args else "unknown cert"


def exam_meta(cert: str) -> ExamMeta:
    try:
        return CERT_EXAMS[cert]
    except KeyError:
        raise UnknownCert(
            f"no exam mapping for cert {cert!r}; known certs: {', '.join(sorted(CERT_EXAMS)) or '(none)'}"
        ) from None


def build_envelope(cert: str, questions: list[dict]) -> dict:
    """Wrap per-question dicts in the exam envelope for `cert`.

    Server-seeded exams get only slug+name (the seed owns the rest); non-seeded
    exams get the full metadata since the crawler is authoritative for them.
    """
    meta = exam_meta(cert)
    envelope: dict = {"slug": meta.slug, "name": meta.name, "questions": questions}
    if not meta.server_seeded:
        envelope["description"] = meta.description
        envelope["questionsPerSession"] = meta.questions_per_session
        envelope["durationMinutes"] = meta.duration_minutes
        envelope["sortOrder"] = meta.sort_order
        envelope["active"] = meta.active
    return envelope
