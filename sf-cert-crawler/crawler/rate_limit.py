from __future__ import annotations

import threading
import time
from urllib.parse import urlparse

from .logging_setup import get_logger

log = get_logger(__name__)


class DomainThrottle:
    """Enforces a minimum interval between requests to the same host.

    ``default_rate_per_sec`` applies to every host; individual hosts can be set
    tighter or looser with :meth:`set_rate` (e.g. a stricter limit for a source
    we're being extra careful with).
    """

    def __init__(self, default_rate_per_sec: float = 1.0) -> None:
        self._default_interval = 1.0 / default_rate_per_sec
        self._intervals: dict[str, float] = {}
        self._last_request: dict[str, float] = {}
        self._lock = threading.Lock()

    def set_rate(self, host: str, rate_per_sec: float) -> None:
        self._intervals[host.lower()] = 1.0 / rate_per_sec

    def wait(self, url: str) -> None:
        host = urlparse(url).netloc.lower()
        with self._lock:
            interval = self._intervals.get(host, self._default_interval)
            last = self._last_request.get(host)
            now = time.monotonic()
            if last is not None and (now - last) < interval:
                delay = interval - (now - last)
                log.debug("throttle %s: sleeping %.2fs", host, delay)
                time.sleep(delay)
            self._last_request[host] = time.monotonic()
