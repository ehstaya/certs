"""Heuristic pre-LLM filter for paid/dump content.

Cheap regex/host checks run before any LLM call. Returns a `FilterResult` whose
`matched_rules` field lets `logs/filtered.jsonl` audits explain *why* an item
was dropped — important because false positives here are silent (the item never
makes it to the LLM, never reaches the DB).

The filter has three outcomes:

- `keep`: nothing matched, item proceeds to the LLM classify step.
- `review`: grey-zone signals (e.g. a user recalling that the exam asked about
  sharing rules, without republishing the question). Logged to
  `logs/review.jsonl` for human triage.
- `drop`: a strong dump/paid signal matched. Logged to `logs/filtered.jsonl`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urlparse

# Hosts known to publish or sell exam dumps. Matched as substrings of the netloc
# so `www.examtopics.com` and bare `examtopics.com` both hit. Add new hosts here
# as we learn about them.
DUMP_HOSTS: tuple[str, ...] = (
    "examtopics.com",
    "certkillers",
    "passleader",
    "braindumps",
    "dumpscollection",
    "exam-labs.com",
    "itexams.com",
    "certificationdumps",
    "killexams",
    "dumpsbase",
    "dumpsacademy",
)

# Hosts that primarily sell paid content. Distinct from dump hosts: not illicit,
# just out of scope for a "free public material only" crawler.
PAID_HOSTS: tuple[str, ...] = (
    "udemy.com",
    "pluralsight.com",
    "focusonforce.com",
)

# Phrases that strongly suggest dump content. Lowercased substring match.
DUMP_PHRASES: tuple[str, ...] = (
    "exam dump",
    "real exam questions",
    "actual exam questions",
    "leaked questions",
    "leaked exam",
    "100% pass",
    "verified answers",
    "pdf dump",
    "braindump",
    "brain dump",
)

# "buy/sell/paid/premium/pdf/download" within 40 chars of "dump/questions/answers".
# Word-boundaried so "I bought them" doesn't false-positive on "buy".
SUSPICIOUS_OFFER_RE = re.compile(
    r"\b(buy|sell|paid|premium|pdf|download)\b.{0,40}\b(dump|questions|answers)\b",
    re.IGNORECASE,
)

# Matches lines starting with `Q1.`, `Q 1.`, `1.`, `1)`, etc. We use this to
# count sequentially numbered items — a long run is a dump signature.
NUMBERED_Q_RE = re.compile(r"(?:^|\n)\s*(?:Q\s*)?(\d+)[.)]\s+", re.IGNORECASE)

# >15 sequentially numbered questions in a single post = almost certainly a dump.
SEQUENTIAL_Q_DROP_THRESHOLD = 15

# Grey-zone phrases: user is talking *about* exam content without republishing it.
GREY_RECALL_PHRASES: tuple[str, ...] = (
    "saw a question on the exam",
    "got a question about",
    "there was a question",
    "the exam asked",
    "remember a question",
    "one of the questions on the exam",
)

URL_IN_TEXT_RE = re.compile(r"""https?://[^\s)\]<>"']+""")


Decision = Literal["keep", "review", "drop"]


@dataclass
class FilterResult:
    decision: Decision
    reason: str
    matched_rules: list[str] = field(default_factory=list)


def check(*, url: str, title: str = "", body: str = "") -> FilterResult:
    """Apply heuristics in order; first decisive rule wins.

    Order matters: cheapest/most-specific checks first so we drop obvious garbage
    before scanning for grey-zone signals.
    """
    combined = f"{title}\n{body}".lower()

    # 1. URL host blocklist.
    host = urlparse(url).netloc.lower()
    if hit := _match_host(host, DUMP_HOSTS):
        return FilterResult("drop", f"url host on dump blocklist: {hit}", [f"host:{hit}"])
    if hit := _match_host(host, PAID_HOSTS):
        return FilterResult(
            "drop",
            f"url host on paid-content blocklist: {hit}",
            [f"paid-host:{hit}"],
        )

    # 2. Linked-host blocklist: a blog or comment that points readers at a dump host.
    for link in URL_IN_TEXT_RE.findall(body):
        link_host = urlparse(link).netloc.lower()
        if hit := _match_host(link_host, DUMP_HOSTS):
            return FilterResult(
                "drop",
                f"body links to dump host: {hit}",
                [f"linked-host:{hit}"],
            )

    # 3. Dump-phrase match in title or body.
    phrase_hits = [f"phrase:{p}" for p in DUMP_PHRASES if p in combined]
    if phrase_hits:
        return FilterResult("drop", "dump phrase in title/body", phrase_hits)

    # 4. Suspicious offer pattern (buy/sell/pdf ... questions/answers/dump).
    if m := SUSPICIOUS_OFFER_RE.search(combined):
        return FilterResult(
            "drop",
            f"suspicious offer pattern: {m.group(0)!r}",
            [f"offer:{m.group(0).strip()}"],
        )

    # 5. Long sequentially-numbered question run = dump signature.
    numbers = [int(n) for n in NUMBERED_Q_RE.findall(body)]
    sequential = _max_consecutive_run(numbers)
    if sequential > SEQUENTIAL_Q_DROP_THRESHOLD:
        return FilterResult(
            "drop",
            f"contains {sequential} sequentially numbered questions",
            [f"numbered-q-run:{sequential}"],
        )

    # 6. Grey zone: poster recalls exam content without republishing it.
    grey = [f"grey:{p}" for p in GREY_RECALL_PHRASES if p in combined]
    if grey:
        return FilterResult(
            "review",
            "mentions exam-question recall, not republished — flag for human review",
            grey,
        )

    return FilterResult("keep", "no filter rules matched", [])


def _match_host(host: str, patterns: tuple[str, ...]) -> str | None:
    host = host.lower()
    for p in patterns:
        if p in host:
            return p
    return None


def _max_consecutive_run(numbers: list[int]) -> int:
    """Longest run of consecutive integers in the order they appeared.

    We don't require the run to start at 1 — `17,18,19,...` is still a dump.
    """
    if not numbers:
        return 0
    best = 1
    run = 1
    for prev, curr in zip(numbers, numbers[1:]):
        if curr == prev + 1:
            run += 1
            if run > best:
                best = run
        else:
            run = 1
    return best
