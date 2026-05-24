from __future__ import annotations

import logging
import sys


def setup_logging(*, verbose: bool = False, quiet: bool = False) -> None:
    level = logging.WARNING if quiet else (logging.DEBUG if verbose else logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s", "%H:%M:%S")
    )
    logger = logging.getLogger("crawler")
    logger.handlers.clear()
    logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False


def get_logger(name: str) -> logging.Logger:
    if name == "crawler" or name.startswith("crawler."):
        return logging.getLogger(name)
    return logging.getLogger(f"crawler.{name}")
