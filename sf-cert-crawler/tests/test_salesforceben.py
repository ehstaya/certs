"""Tests for the Salesforce Ben source — deterministic question extraction
from AYS "Quiz Maker" markup. No network: runs against a saved HTML fixture.
"""

from __future__ import annotations

from pathlib import Path

from crawler.models import RawItem
from crawler.pipeline.filter_paid import check as filter_check
from crawler.sources.salesforceben import SalesforceBenSource

FIXTURE = Path(__file__).parent / "fixtures" / "sfben_quiz_min.html"
URL = "https://www.salesforceben.com/salesforce-admin-practice-exam/"


def _html() -> str:
    return FIXTURE.read_text(encoding="utf-8")


def _item() -> RawItem:
    return RawItem(source_name="salesforceben", source_type="blog", url=URL)


def test_extract_questions_from_ays_quiz() -> None:
    questions = SalesforceBenSource().extract_questions(_html(), _item(), cert="admin")
    assert len(questions) == 2, questions

    q1, q2 = questions

    assert "organization-wide default" in q1.question_text.lower()
    assert q1.options == [
        "A user's profile permissions",
        "The baseline level of access users have to each other's records",
        "Field-level security on a page layout",
    ]
    assert q1.correct_answer == "The baseline level of access users have to each other's records"
    assert q1.explanation is not None and "baseline level of access" in q1.explanation
    assert q1.cert == "admin"
    assert q1.source_url == URL
    assert q1.source_type == "blog"
    assert q1.confidence == 1.0
    assert q1.difficulty is None
    assert q1.topic is None
    assert len(q1.id) == 16 and all(c in "0123456789abcdef" for c in q1.id)

    assert "record-triggered process" in q2.question_text.lower()
    assert q2.options == ["An Apex trigger", "Flow"]
    assert q2.correct_answer == "Flow"
    assert q2.explanation is not None and "declaratively" in q2.explanation

    # ids are content-derived and distinct.
    assert q1.id != q2.id


def test_extract_questions_is_stable() -> None:
    # Same input -> same ids (the id is a hash of the normalized question text).
    a = SalesforceBenSource().extract_questions(_html(), _item(), cert="admin")
    b = SalesforceBenSource().extract_questions(_html(), _item(), cert="admin")
    assert [q.id for q in a] == [q.id for q in b]


def test_no_questions_when_no_quiz_markup() -> None:
    plain = "<html><body><article><div class='entry-content'><h1>Just a blog post</h1>"
    plain += "<p>No quiz here, only prose about sharing rules and profiles.</p></div></article></body></html>"
    assert SalesforceBenSource().extract_questions(plain, _item(), cert="admin") == []


def test_parse_returns_title_and_body() -> None:
    title, text = SalesforceBenSource().parse(_html(), _item())
    assert title == "Salesforce Admin Practice Exam"
    assert "practice questions" in text.lower()


def test_practice_exam_fixture_passes_paid_filter() -> None:
    # Sanity: a legitimate practice-exam page should survive the dump filter.
    title, text = SalesforceBenSource().parse(_html(), _item())
    result = filter_check(url=URL, title=title or "", body=text)
    assert result.decision == "keep", result
