from __future__ import annotations

import urllib.robotparser
from urllib.parse import urljoin, urlparse

import httpx

from .config import settings
from .logging_setup import get_logger

log = get_logger(__name__)


class RobotsCache:
    """One ``RobotFileParser`` per scheme://host.

    Fail-closed: if robots.txt can't be fetched or returns a server error we
    treat the whole host as disallowed — we'd rather skip than crawl something
    we shouldn't. A missing robots.txt (HTTP 404) means "allow all", per the
    usual convention.
    """

    def __init__(self, user_agent: str | None = None) -> None:
        self.user_agent = user_agent or settings.user_agent
        self._cache: dict[str, urllib.robotparser.RobotFileParser | None] = {}

    @staticmethod
    def _origin(url: str) -> str:
        parts = urlparse(url)
        return f"{parts.scheme}://{parts.netloc}"

    def _parser(self, url: str) -> urllib.robotparser.RobotFileParser | None:
        origin = self._origin(url)
        if origin in self._cache:
            return self._cache[origin]

        robots_url = urljoin(origin, "/robots.txt")
        parser = urllib.robotparser.RobotFileParser()
        parser.set_url(robots_url)
        result: urllib.robotparser.RobotFileParser | None
        try:
            resp = httpx.get(
                robots_url,
                headers={"User-Agent": self.user_agent},
                timeout=settings.request_timeout_s,
                follow_redirects=True,
            )
            if resp.status_code == 404:
                parser.parse([])  # no robots.txt -> allow all
                result = parser
            elif resp.status_code >= 400:
                log.warning(
                    "robots.txt %s -> HTTP %s; treating host as disallow-all",
                    robots_url,
                    resp.status_code,
                )
                result = None
            else:
                parser.parse(resp.text.splitlines())
                result = parser
        except httpx.HTTPError as exc:
            log.warning("robots.txt %s failed (%s); treating host as disallow-all", robots_url, exc)
            result = None

        self._cache[origin] = result
        return result

    def can_fetch(self, url: str) -> bool:
        parser = self._parser(url)
        if parser is None:
            return False
        return parser.can_fetch(self.user_agent, url)
