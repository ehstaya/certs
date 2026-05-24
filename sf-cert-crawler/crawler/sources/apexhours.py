from __future__ import annotations

import xml.etree.ElementTree as ET
from typing import TYPE_CHECKING
from urllib.parse import urljoin, urlparse

import httpx

from ..logging_setup import get_logger
from ..models import RawItem
from .base import Source, parse_wp_article

if TYPE_CHECKING:
    from ..fetch import Fetcher

log = get_logger(__name__)

_SITEMAP_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
_POST_SITEMAP_PATH = "/post-sitemap.xml"
_HOSTS = {"www.apexhours.com", "apexhours.com"}
# Apex Hours has no cert-specific category archive, so we discover from the post
# sitemap and keep posts whose slug looks certification-related.
_CERT_SLUG_KEYWORDS = ("admin", "administrator", "certif", "exam")


class ApexHoursSource(Source):
    name = "apexhours"
    source_type = "blog"
    rate_per_sec = 1.0
    base_url = "https://www.apexhours.com"
    license_note = "Apex Hours (apexhours.com); personal study use only"

    def discover(self, fetcher: "Fetcher", *, limit: int, use_cache: bool = True) -> list[RawItem]:
        url = urljoin(self.base_url, _POST_SITEMAP_PATH)
        try:
            doc = fetcher.fetch(url, use_cache=use_cache)
        except httpx.HTTPStatusError as exc:
            log.warning("post-sitemap %s -> HTTP %s; no candidates", url, exc.response.status_code)
            return []
        entries = self._parse_sitemap(doc.text)
        relevant = [(loc, lastmod) for loc, lastmod in entries if self._is_cert_relevant(loc)]
        relevant.sort(key=lambda pair: pair[1], reverse=True)  # ISO-8601 lastmod sorts lexicographically
        items: list[RawItem] = []
        seen: set[str] = set()
        for loc, _ in relevant:
            canonical = self._canonical(loc)
            if canonical is None or canonical in seen:
                continue
            seen.add(canonical)
            items.append(RawItem(source_name=self.name, source_type=self.source_type, url=canonical))
            if len(items) >= limit:
                break
        log.info(
            "apexhours: %d cert-relevant post(s) of %d in sitemap; taking %d",
            len(relevant), len(entries), len(items),
        )
        return items

    def parse(self, html: str, item: RawItem) -> tuple[str | None, str]:
        return parse_wp_article(html, fallback_title=item.title)

    @staticmethod
    def _parse_sitemap(xml_text: str) -> list[tuple[str, str]]:
        out: list[tuple[str, str]] = []
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as exc:
            log.warning("could not parse sitemap XML: %s", exc)
            return out
        for url_el in root.findall("sm:url", _SITEMAP_NS):
            loc_el = url_el.find("sm:loc", _SITEMAP_NS)
            if loc_el is None or not loc_el.text:
                continue
            lastmod_el = url_el.find("sm:lastmod", _SITEMAP_NS)
            lastmod = lastmod_el.text.strip() if lastmod_el is not None and lastmod_el.text else ""
            out.append((loc_el.text.strip(), lastmod))
        return out

    @staticmethod
    def _is_cert_relevant(loc: str) -> bool:
        path = urlparse(loc).path.strip("/").lower()
        return any(keyword in path for keyword in _CERT_SLUG_KEYWORDS)

    @staticmethod
    def _canonical(loc: str) -> str | None:
        parts = urlparse(loc)
        if parts.netloc.lower() not in _HOSTS:
            return None
        path = parts.path if parts.path.endswith("/") else parts.path + "/"
        return f"https://www.apexhours.com{path}"
