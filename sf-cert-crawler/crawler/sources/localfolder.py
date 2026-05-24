from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..config import settings
from ..logging_setup import get_logger
from ..models import RawItem
from .base import Source, html_to_text

if TYPE_CHECKING:
    from ..fetch import Fetcher

log = get_logger(__name__)

_TEXT_EXT = {".txt", ".text", ".md", ".markdown"}
_HTML_EXT = {".html", ".htm"}
_PDF_EXT = {".pdf"}
_IMAGE_MIME = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
}
_IMAGE_EXT = set(_IMAGE_MIME.keys())
_ALL_EXT = _TEXT_EXT | _HTML_EXT | _PDF_EXT | _IMAGE_EXT


class LocalFolderUnavailable(RuntimeError):
    """The local-folder source can't be used — LOCAL_SOURCE_DIR not set / not a directory."""


class LocalFolderSource(Source):
    """Reads study material from a local directory (internal agent only).

    Picks up ``.txt`` / ``.md`` / ``.html`` / ``.pdf`` files (recursively),
    newest first. Useful for material you don't want in (or can't get from)
    Drive — e.g. NotebookLM exports you've downloaded.
    """

    name = "localfolder"
    source_type = "other"
    license_note = "local study material; personal study use only"
    fetch_via_http = False
    trust_source = True  # admin-curated (uploads UI / explicit drop folder); skip the dump-check
    base_url = ""

    def __init__(self) -> None:
        configured = settings.local_source_dir
        if not configured:
            raise LocalFolderUnavailable("LOCAL_SOURCE_DIR is not set in .env")
        self._dir = Path(configured).expanduser()
        if not self._dir.is_dir():
            raise LocalFolderUnavailable(f"LOCAL_SOURCE_DIR {self._dir} is not a directory")

    def discover(self, fetcher: "Fetcher | None", *, limit: int, use_cache: bool = True) -> list[RawItem]:
        files = sorted(
            (p for p in self._dir.rglob("*") if p.is_file() and p.suffix.lower() in _ALL_EXT),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        items: list[RawItem] = []
        for p in files[:limit]:
            suffix = p.suffix.lower()
            meta = {"path": str(p.resolve())}
            if suffix in _IMAGE_EXT:
                meta["kind"] = "image"
                meta["mime_type"] = _IMAGE_MIME[suffix]
            items.append(
                RawItem(
                    source_name=self.name,
                    source_type=self.source_type,
                    url=p.resolve().as_uri(),
                    title=p.stem,
                    meta=meta,
                )
            )
        log.info(
            "local folder %s: %d readable file(s) (%d image, %d text/pdf)",
            self._dir, len(items),
            sum(1 for i in items if i.meta.get("kind") == "image"),
            sum(1 for i in items if i.meta.get("kind") != "image"),
        )
        return items

    def fetch_content(self, item: RawItem, *, use_cache: bool = True) -> tuple[str | None, str]:
        path = Path(item.meta.get("path") or "")
        if not path.is_file():
            return item.title, ""
        suffix = path.suffix.lower()
        if suffix in _IMAGE_EXT:
            # Images carry no text; the runner routes them through fetch_image()
            # and the Claude vision extractor instead.
            return item.title, ""
        if suffix in _PDF_EXT:
            return item.title, _pdf_file_to_text(path)
        raw = path.read_text("utf-8", errors="replace")
        if suffix in _HTML_EXT:
            return item.title, html_to_text(raw)
        return item.title, raw

    def fetch_image(self, item: RawItem) -> tuple[bytes, str] | None:
        if item.meta.get("kind") != "image":
            return None
        path = Path(item.meta.get("path") or "")
        mime = item.meta.get("mime_type") or _IMAGE_MIME.get(path.suffix.lower(), "")
        if not path.is_file() or not mime:
            return None
        return path.read_bytes(), mime


def _pdf_file_to_text(path: Path) -> str:
    try:
        from pypdf import PdfReader
    except ImportError:  # pragma: no cover
        log.warning("pypdf not installed — can't read PDF %s; install sf-cert-crawler[internal]", path)
        return ""
    try:
        reader = PdfReader(str(path))
        return "\n".join((page.extract_text() or "") for page in reader.pages)
    except Exception as exc:  # noqa: BLE001
        log.warning("failed to read PDF %s: %s", path, exc)
        return ""
