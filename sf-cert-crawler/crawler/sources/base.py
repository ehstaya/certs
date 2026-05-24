from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

from selectolax.parser import HTMLParser

from ..models import Question, RawItem

if TYPE_CHECKING:
    from ..fetch import Fetcher


class Source(ABC):
    """A content source.

    Two kinds, distinguished by ``fetch_via_http``:

    * **HTTP sources** (``fetch_via_http = True``, the default — web blogs): the
      runner fetches each discovered URL with the shared :class:`crawler.fetch.Fetcher`
      (robots.txt, rate limit, cache, retries), then calls :meth:`parse` for
      ``(title, text)`` and :meth:`extract_questions` for deterministic structured
      extraction.
    * **Non-HTTP sources** (``fetch_via_http = False`` — Google Drive, local
      folder): the runner does not use the Fetcher; the source loads its own
      content via :meth:`fetch_content` returning ``(title, text)``. There's no
      HTML, so structured extraction doesn't apply — these go straight to the
      LLM extraction step.
    """

    name: str
    source_type: str  # one of crawler.models.SOURCE_TYPES
    rate_per_sec: float = 1.0
    base_url: str = ""
    license_note: str = ""
    fetch_via_http: bool = True
    # Admin-curated internal sources (Drive upload, local folder) set this True so
    # the LLM dump-check is skipped — that check is meant to catch leaked exam
    # content from scraped web sources, and false-positives on clean Q&A docs
    # an admin deliberately uploaded.
    trust_source: bool = False

    @abstractmethod
    def discover(self, fetcher: "Fetcher | None", *, limit: int, use_cache: bool = True) -> list[RawItem]:
        """Return up to ``limit`` candidate documents, newest first where possible.

        ``fetcher`` is ``None`` for non-HTTP sources (they discover their own way).
        """

    def parse(self, html: str, item: RawItem) -> tuple[str | None, str]:
        """Extract ``(title, main_text)`` from a fetched HTML document.

        Only called for HTTP sources. Non-HTTP sources don't implement this.
        """
        raise NotImplementedError

    def fetch_content(self, item: RawItem, *, use_cache: bool = True) -> tuple[str | None, str]:
        """Load ``(title, text)`` for a discovered item.

        Only called for non-HTTP sources. HTTP sources don't implement this.
        For image-only content, return ``(title, "")`` and override :meth:`fetch_image`.
        """
        raise NotImplementedError

    def fetch_image(self, item: RawItem) -> tuple[bytes, str] | None:
        """Return ``(image_bytes, mime_type)`` if `item` is an image, else ``None``.

        Default is ``None``. Sources that surface images (e.g. screenshots in a
        local folder) override this; the runner uses it to route through the
        Claude vision extraction path instead of text extraction.
        """
        return None

    def extract_questions(self, html: str, item: RawItem, *, cert: str) -> list[Question]:
        """Deterministically pull structured questions out of a fetched page.

        Default: nothing. Override in sources that host structured quizzes.
        """
        return []


def parse_wp_article(html: str, *, fallback_title: str | None) -> tuple[str | None, str]:
    """Best-effort ``(title, body_text)`` extraction from a WordPress article page.

    Most Salesforce-cert blogs run WordPress: ``<h1>`` for the title, then the
    first of ``div.entry-content`` / ``<article>`` / ``main#primary`` / ``<main>`` /
    ``<body>`` for the body text.
    """
    return _html_to_title_text(html, fallback_title=fallback_title)


def _html_to_title_text(html: str, *, fallback_title: str | None) -> tuple[str | None, str]:
    tree = HTMLParser(html)
    title = fallback_title
    h1 = tree.css_first("h1")
    if h1 is not None and h1.text(strip=True):
        title = h1.text(strip=True)
    body = (
        tree.css_first("div.entry-content")
        or tree.css_first("article")
        or tree.css_first("main#primary")
        or tree.css_first("main")
        or tree.body
    )
    text = body.text(separator="\n", strip=True) if body is not None else ""
    return title, text


def html_to_text(html: str) -> str:
    """Plain-text extraction for arbitrary HTML files (e.g. in a local folder)."""
    _, text = _html_to_title_text(html, fallback_title=None)
    return text
