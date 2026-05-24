from __future__ import annotations

import hashlib
import json
from pathlib import Path

import httpx
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from .config import settings
from .logging_setup import get_logger
from .models import FetchedDoc
from .rate_limit import DomainThrottle
from .robots import RobotsCache

log = get_logger(__name__)

_RETRYABLE_STATUS = {429, 503}


class RobotsDisallowed(Exception):
    """robots.txt forbids fetching this URL for our User-Agent."""


class _RetryableStatus(Exception):
    """Internal: a 429/503 worth retrying with exponential backoff."""


def _cache_file(url: str) -> Path:
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
    return settings.cache_dir / f"{digest}.json"


class Fetcher:
    """Polite HTTP GET: robots.txt check, per-domain throttle, retry-with-backoff
    on 429/503, and an on-disk cache keyed by URL so re-runs don't re-fetch.
    """

    def __init__(
        self,
        *,
        throttle: DomainThrottle | None = None,
        robots: RobotsCache | None = None,
    ) -> None:
        settings.ensure_dirs()
        self.throttle = throttle or DomainThrottle(settings.default_rate_per_sec)
        self.robots = robots or RobotsCache(settings.user_agent)
        self._client = httpx.Client(
            headers={"User-Agent": settings.user_agent},
            timeout=settings.request_timeout_s,
            follow_redirects=True,
        )

    def __enter__(self) -> Fetcher:
        return self

    def __exit__(self, *exc: object) -> None:
        self._client.close()

    def fetch(self, url: str, *, use_cache: bool = True) -> FetchedDoc:
        cache_path = _cache_file(url)
        if use_cache and cache_path.exists():
            data = json.loads(cache_path.read_text("utf-8"))
            log.debug("cache hit: %s", url)
            return FetchedDoc(
                url=url,
                status_code=data["status_code"],
                content_type=data.get("content_type"),
                text=data["text"],
                from_cache=True,
            )

        if not self.robots.can_fetch(url):
            raise RobotsDisallowed(url)

        status, content_type, text = self._get(url)
        cache_path.write_text(
            json.dumps({"url": url, "status_code": status, "content_type": content_type, "text": text}),
            "utf-8",
        )
        return FetchedDoc(
            url=url, status_code=status, content_type=content_type, text=text, from_cache=False
        )

    @retry(
        retry=retry_if_exception_type((_RetryableStatus, httpx.TransportError)),
        wait=wait_exponential(multiplier=1, min=1, max=30),
        stop=stop_after_attempt(settings.max_retries),
        reraise=True,
    )
    def _get(self, url: str) -> tuple[int, str | None, str]:
        self.throttle.wait(url)
        log.info("GET %s", url)
        resp = self._client.get(url)
        if resp.status_code in _RETRYABLE_STATUS:
            log.warning("%s -> HTTP %s; backing off", url, resp.status_code)
            raise _RetryableStatus(f"{url} -> {resp.status_code}")
        resp.raise_for_status()
        return resp.status_code, resp.headers.get("content-type"), resp.text
