"""Critical tests for the paid/dump filter.

These six fixtures come straight from the build brief. They must all pass
before any crawler logic is written — a wrong decision here is silent
(items never reach the DB or the LLM), so the audit trail matters.

Each test asserts both the decision and inspects `matched_rules` so that a
future regression that changes *why* an item is dropped is also caught.
"""

from __future__ import annotations

from crawler.pipeline.filter_paid import check


def test_passed_exam_writeup_is_kept() -> None:
    # Genuine post-exam debrief, no recall of specific questions, no dump signals.
    result = check(
        url="https://www.reddit.com/r/salesforce/comments/abc/just_passed_admin/",
        title="Just passed my Admin exam, here are my study tips",
        body=(
            "Took the Admin cert yesterday. My approach was Trailhead trails plus "
            "the FocusOnForce practice tests (I bought them, worth it). Make sure "
            "you really understand sharing rules, profiles vs permission sets, "
            "and approval processes. Good luck everyone!"
        ),
    )
    assert result.decision == "keep", result


def test_titled_pdf_dump_is_dropped() -> None:
    # Title screams dump — should be dropped on phrase match alone.
    result = check(
        url="https://www.reddit.com/r/sfdc/comments/xyz/admin_pdf/",
        title="Salesforce Admin Real Exam Questions PDF — 240 questions verified",
        body="DM me for the PDF download, 100% pass guaranteed.",
    )
    assert result.decision == "drop", result
    # Either the title phrase or the body's '100% pass' should have matched.
    assert any("phrase:" in r for r in result.matched_rules), result


def test_sequential_numbered_questions_is_dropped() -> None:
    # 47 numbered questions in a row = dump signature, regardless of host.
    body_lines: list[str] = []
    for i in range(1, 48):
        body_lines.append(f"Q{i}. Which of the following best describes feature {i}?")
        body_lines.append("A) Option A")
        body_lines.append("B) Option B")
        body_lines.append("C) Option C")
        body_lines.append("D) Option D")
        body_lines.append("")
    result = check(
        url="https://example-forum.test/post/sharing-questions",
        title="Practice questions",
        body="\n".join(body_lines),
    )
    assert result.decision == "drop", result
    assert any(r.startswith("numbered-q-run:") for r in result.matched_rules), result


def test_trailhead_style_knowledge_check_is_kept() -> None:
    # Educational MCQ from a module-style page. The filter is source-agnostic;
    # it only sees URL+text, so it should pass even though we don't actually
    # crawl Trailhead in this project.
    result = check(
        url="https://trailhead.salesforce.com/content/learn/modules/data_security/check",
        title="Check Your Understanding",
        body=(
            "Which sharing setting determines the default level of access users "
            "have to each other's records?\n"
            "A) Profile permissions\n"
            "B) Organization-wide defaults\n"
            "C) Role hierarchy\n"
            "D) Manual sharing"
        ),
    )
    assert result.decision == "keep", result


def test_blog_linking_to_examtopics_is_dropped() -> None:
    # Body links to a blocklisted dump host — drop even if the post itself reads
    # like a normal blog post.
    result = check(
        url="https://random-blog.example/admin-prep-2026",
        title="Best resources for the Salesforce Admin exam",
        body=(
            "Some people use https://www.examtopics.com/exams/salesforce/admin/ but "
            "I prefer Trailhead. The official exam guide is also helpful."
        ),
    )
    assert result.decision == "drop", result
    assert any(r.startswith("linked-host:") for r in result.matched_rules), result


def test_genuine_exam_recall_is_flagged_for_review() -> None:
    # Poster mentions a question they saw on the exam but doesn't republish it.
    # Grey zone — flag for human review, don't auto-drop or auto-keep.
    result = check(
        url="https://www.reddit.com/r/salesforce/comments/def/post_exam/",
        title="Took the Admin exam this morning",
        body=(
            "Felt good overall. I saw a question on the exam about sharing rules "
            "that I wasn't sure on, going to review that area more. Anyone else "
            "find the questions on Flow tricky?"
        ),
    )
    assert result.decision == "review", result
    assert any(r.startswith("grey:") for r in result.matched_rules), result
