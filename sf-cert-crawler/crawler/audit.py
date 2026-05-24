from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from .config import settings

# Append-only JSONL audit trails. `filtered.jsonl` records items the heuristic
# filter dropped; `review.jsonl` records grey-zone items flagged for a human to
# look at. Both let you reconstruct *why* something didn't make it into the DB.


def _append(path: Path, record: dict) -> None:
    settings.ensure_dirs()
    line = {"ts": datetime.now(timezone.utc).isoformat(), **record}
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False) + "\n")


def log_filtered(*, source: str, url: str, reason: str, rules: list[str]) -> None:
    _append(settings.logs_dir / "filtered.jsonl", {"source": source, "url": url, "reason": reason, "rules": rules})


def log_review(*, source: str, url: str, reason: str, rules: list[str]) -> None:
    _append(settings.logs_dir / "review.jsonl", {"source": source, "url": url, "reason": reason, "rules": rules})
