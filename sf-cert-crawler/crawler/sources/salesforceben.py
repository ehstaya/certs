from __future__ import annotations

import base64
import binascii
import hashlib
import json
import re
from datetime import datetime, timezone
from typing import TYPE_CHECKING
from urllib.parse import urljoin, urlparse

import httpx
from selectolax.parser import HTMLParser, Node

from ..logging_setup import get_logger
from ..models import Question, RawItem
from .base import Source, parse_wp_article

if TYPE_CHECKING:
    from ..fetch import Fetcher

log = get_logger(__name__)

_HOSTS = {"www.salesforceben.com", "salesforceben.com"}
_ARCHIVE_PATH = "/category/certifications/"
_MAX_ARCHIVE_PAGES = 6  # ~10 posts/page — plenty to cover "latest N"

# Single-segment slugs on this site that are standalone pages, not articles.
_NON_ARTICLE_SLUGS = {
    "about",
    "advertise-with-us",
    "contact",
    "influencer-program",
    "newsletters",
    "privacy-policy",
    "resources",
    "salesforce-events",
    "where-to-start",
    "write-for-us",
    "get-salesforce-certified",
    "salesforce-certifications",
    "what-is-pledge-1",
}
# First-segment prefixes that are never article permalinks.
_NON_ARTICLE_PREFIXES = {"category", "tag", "author", "page", "wp-content", "wp-json", "feed"}
# Article permalinks are a single slug segment, e.g. /salesforce-admin-practice-exam/.
_SINGLE_SEGMENT_RE = re.compile(r"^/([a-z0-9][a-z0-9-]{4,})/$")

# --- AYS "Quiz Maker" WordPress plugin markup -----------------------------------
# Per question:
#   <div class="ays_quiz_question"><p>QUESTION</p></div>
#   <div class="ays-quiz-answers ...">
#     <div class="ays-field ...">
#       <input type="radio" id="ays-answer-<id>-<n>" value="<id>"/>
#       <label for="ays-answer-<id>-<n>">ANSWER TEXT</label>
#     </div> ... (one per option) ...
#     <script>window.quizOptions_<n>['<qid>'] = '<base64>';</script>
#   </div>
#   <div class="right_answer_text">EXPLANATION</div>   (rendered separately)
# The base64 decodes to {"question_answer": {"<answerId>": "0"|"1", ...}} — the
# id mapped to "1" is the correct answer. (The visible `ays_answer_correct[]`
# hidden inputs are all zeroed out, so the script blob is the only answer key.)
_QUIZ_KEY_RE = re.compile(
    r"quizOptions_\d+\s*\[\s*['\"]?\d+['\"]?\s*\]\s*=\s*['\"]([A-Za-z0-9+/=]+)['\"]"
)


def _collapse(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _clean_text(node: Node | None, *, block: bool) -> str | None:
    if node is None:
        return None
    raw = node.text(separator="\n" if block else " ", strip=True)
    if not raw:
        return None
    if block:
        lines = [_collapse(ln) for ln in raw.splitlines()]
        cleaned = "\n".join(ln for ln in lines if ln)
    else:
        cleaned = _collapse(raw)
    return cleaned or None


def _question_id(question_text: str) -> str:
    return hashlib.sha256(_collapse(question_text).lower().encode("utf-8")).hexdigest()[:16]


class SalesforceBenSource(Source):
    name = "salesforceben"
    source_type = "blog"
    rate_per_sec = 1.0
    base_url = "https://www.salesforceben.com"
    license_note = "Salesforce Ben (salesforceben.com); personal study use only"

    # --- discovery -------------------------------------------------------------

    def discover(self, fetcher: "Fetcher", *, limit: int, use_cache: bool = True) -> list[RawItem]:
        items: list[RawItem] = []
        seen: set[str] = set()
        for page in range(1, _MAX_ARCHIVE_PAGES + 1):
            if len(items) >= limit:
                break
            path = _ARCHIVE_PATH if page == 1 else f"{_ARCHIVE_PATH}page/{page}/"
            url = urljoin(self.base_url, path)
            try:
                doc = fetcher.fetch(url, use_cache=use_cache)
            except httpx.HTTPStatusError as exc:
                log.info("archive page %s -> HTTP %s; stopping discovery", page, exc.response.status_code)
                break
            added = 0
            for link, title in self._article_links(doc.text):
                if link in seen:
                    continue
                seen.add(link)
                items.append(
                    RawItem(source_name=self.name, source_type=self.source_type, url=link, title=title)
                )
                added += 1
                if len(items) >= limit:
                    break
            log.info("archive page %s: %d new article link(s)", page, added)
            if added == 0:
                break
        return items[:limit]

    def _article_links(self, html: str) -> list[tuple[str, str | None]]:
        tree = HTMLParser(html)
        main = tree.css_first("main#primary") or tree.css_first("main") or tree.body
        if main is None:
            return []
        out: list[tuple[str, str | None]] = []
        seen_local: set[str] = set()
        for article in main.css("article"):
            heading = article.css_first("h2") or article.css_first("h3")
            heading_text = heading.text(strip=True) if heading is not None else None
            for anchor in article.css("a[href]"):
                href = anchor.attributes.get("href")
                canonical = self._article_url(href) if href else None
                if canonical is None or canonical in seen_local:
                    continue
                seen_local.add(canonical)
                out.append((canonical, heading_text or (anchor.text(strip=True) or None)))
                break  # first valid permalink inside the <article> is enough
        return out

    def _article_url(self, href: str) -> str | None:
        absolute = urljoin(self.base_url, href)
        parts = urlparse(absolute)
        if parts.netloc.lower() not in _HOSTS:
            return None
        path = parts.path if parts.path.endswith("/") else parts.path + "/"
        match = _SINGLE_SEGMENT_RE.match(path)
        if not match:
            return None
        slug = match.group(1)
        if slug in _NON_ARTICLE_SLUGS or slug in _NON_ARTICLE_PREFIXES:
            return None
        return f"https://www.salesforceben.com{path}"

    # --- parsing / extraction --------------------------------------------------

    def parse(self, html: str, item: RawItem) -> tuple[str | None, str]:
        return parse_wp_article(html, fallback_title=item.title)

    def extract_questions(self, html: str, item: RawItem, *, cert: str) -> list[Question]:
        tree = HTMLParser(html)
        question_nodes = tree.css("div.ays_quiz_question")
        answer_nodes = tree.css("div.ays-quiz-answers")
        if not question_nodes or not answer_nodes:
            return []
        count = min(len(question_nodes), len(answer_nodes))
        if len(question_nodes) != len(answer_nodes):
            log.warning(
                "AYS quiz markup mismatch: %d question divs vs %d answer divs; using first %d",
                len(question_nodes),
                len(answer_nodes),
                count,
            )
        explanation_nodes = tree.css("div.right_answer_text")
        use_explanations = len(explanation_nodes) == len(question_nodes)

        out: list[Question] = []
        seen_ids: set[str] = set()
        for i in range(count):
            question_text = _clean_text(question_nodes[i], block=False)
            if not question_text:
                continue
            options, id_to_text = self._parse_options(answer_nodes[i])
            if len(options) < 2:
                continue
            correct = self._correct_answer(answer_nodes[i], id_to_text)
            explanation = _clean_text(explanation_nodes[i], block=True) if use_explanations else None
            qid = _question_id(question_text)
            if qid in seen_ids:
                continue
            seen_ids.add(qid)
            out.append(
                Question(
                    id=qid,
                    cert=cert,
                    topic=None,
                    question_text=question_text,
                    options=options,
                    correct_answer=correct,
                    explanation=explanation,
                    difficulty=None,
                    source_url=item.url,
                    source_type=self.source_type,
                    source_license_note=self.license_note,
                    extracted_at=datetime.now(timezone.utc),
                    confidence=1.0,
                )
            )
        return out

    @staticmethod
    def _parse_options(answers_node: Node) -> tuple[list[str], dict[str, str]]:
        # AYS uses radio inputs for single-answer questions and checkboxes for
        # multi-response ("select all that apply") questions; handle both.
        options: list[str] = []
        id_to_text: dict[str, str] = {}
        for field in answers_node.css("div.ays-field"):
            choice = field.css_first('input[type="radio"]') or field.css_first('input[type="checkbox"]')
            if choice is None:
                continue
            answer_id = choice.attributes.get("value")
            if not answer_id:
                continue
            text: str | None = None
            for label in field.css("label"):
                if "ays_answer_image" in (label.attributes.get("class") or ""):
                    continue
                candidate = label.text(strip=True)
                if candidate:
                    text = _collapse(candidate)
                    break
            if text:
                options.append(text)
                id_to_text[answer_id] = text
        return options, id_to_text

    @staticmethod
    def _correct_answer(answers_node: Node, id_to_text: dict[str, str]) -> str | None:
        script = answers_node.css_first("script")
        if script is None:
            return None
        match = _QUIZ_KEY_RE.search(script.text() or "")
        if not match:
            return None
        try:
            decoded = json.loads(base64.b64decode(match.group(1)))
        except (binascii.Error, ValueError):
            return None
        question_answer = decoded.get("question_answer") if isinstance(decoded, dict) else None
        if not isinstance(question_answer, dict):
            return None
        correct = [id_to_text[k] for k, v in question_answer.items() if str(v) == "1" and k in id_to_text]
        if len(correct) == 1:
            return correct[0]
        if len(correct) > 1:
            return "; ".join(correct)  # multi-select question
        return None
