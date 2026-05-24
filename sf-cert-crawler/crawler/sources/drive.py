from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
from typing import TYPE_CHECKING

from ..config import settings
from ..logging_setup import get_logger
from ..models import RawItem
from .base import Source, html_to_text

if TYPE_CHECKING:
    from ..fetch import Fetcher

log = get_logger(__name__)

_SCOPES = ["https://www.googleapis.com/auth/drive.readonly"]
# Google-native types -> the export MIME type we request.
_EXPORT_MIME = {
    "application/vnd.google-apps.document": "text/plain",
    "application/vnd.google-apps.spreadsheet": "text/csv",
    "application/vnd.google-apps.presentation": "text/plain",
}
# Regular (binary) types we know how to turn into text.
_READABLE_BINARY = {
    "text/plain", "text/markdown", "text/csv", "text/html",
    "application/json", "application/pdf",
}
_READABLE_TYPES = set(_EXPORT_MIME) | _READABLE_BINARY


class DriveUnavailable(RuntimeError):
    """Google Drive can't be used — missing config or the google-* libraries."""


def _cache_path(url: str) -> Path:
    return settings.cache_dir / f"{hashlib.sha256(url.encode('utf-8')).hexdigest()[:24]}.json"


class DriveSource(Source):
    """Reads study material from a Google Drive folder (internal agent only).

    Auth: a service-account JSON file (recommended for headless runs) *or* an
    OAuth user-token JSON. Reads one folder, newest files first; exports Google
    Docs as plain text, Sheets as CSV, Slides as plain text; downloads regular
    text/markdown/csv/html/json/pdf files. Not recursive into subfolders (v1).
    """

    name = "drive"
    source_type = "other"
    license_note = "Google Drive (internal); personal study use only"
    fetch_via_http = False
    trust_source = True  # admin-curated; skip the dump-check
    base_url = "https://drive.google.com"

    def __init__(self) -> None:
        self._folder_id = settings.google_drive_folder_id
        if not self._folder_id:
            raise DriveUnavailable("GOOGLE_DRIVE_FOLDER_ID is not set in .env")
        service_account = settings.google_service_account_file
        oauth_token = settings.google_oauth_token_file
        if not service_account and not oauth_token:
            raise DriveUnavailable(
                "no Google credentials configured — set GOOGLE_SERVICE_ACCOUNT_FILE or "
                "GOOGLE_OAUTH_TOKEN_FILE in .env"
            )
        self._cred_file = Path(service_account or oauth_token).expanduser()  # type: ignore[arg-type]
        self._is_service_account = bool(service_account)
        self._service = None  # built lazily on first use

    def _build_service(self):  # noqa: ANN201 - google client type
        if self._service is not None:
            return self._service
        try:
            from googleapiclient.discovery import build
        except ImportError as exc:  # pragma: no cover — only when the extra isn't installed
            raise DriveUnavailable(
                "google-api-python-client is not installed — run `pip install 'sf-cert-crawler[internal]'`"
            ) from exc
        if not self._cred_file.is_file():
            raise DriveUnavailable(f"Google credentials file not found: {self._cred_file}")
        if self._is_service_account:
            from google.oauth2.service_account import Credentials

            creds = Credentials.from_service_account_file(str(self._cred_file), scopes=_SCOPES)
        else:
            from google.oauth2.credentials import Credentials

            creds = Credentials.from_authorized_user_file(str(self._cred_file), scopes=_SCOPES)
        self._service = build("drive", "v3", credentials=creds, cache_discovery=False)
        return self._service

    def discover(self, fetcher: "Fetcher | None", *, limit: int, use_cache: bool = True) -> list[RawItem]:
        service = self._build_service()
        page_size = max(10, min(limit, 100))
        resp = (
            service.files()
            .list(
                q=f"'{self._folder_id}' in parents and trashed = false",
                orderBy="modifiedTime desc",
                pageSize=page_size,
                fields="files(id, name, mimeType, modifiedTime)",
                supportsAllDrives=True,
                includeItemsFromAllDrives=True,
            )
            .execute()
        )
        files = resp.get("files", [])
        items: list[RawItem] = []
        skipped = 0
        for f in files:
            mime = f.get("mimeType", "")
            if mime not in _READABLE_TYPES:
                skipped += 1
                continue
            file_id = f["id"]
            name = f.get("name") or file_id
            items.append(
                RawItem(
                    source_name=self.name,
                    source_type=self.source_type,
                    url=self._view_url(file_id, mime),
                    title=name,
                    meta={"file_id": file_id, "mime_type": mime, "modified_time": f.get("modifiedTime", "")},
                )
            )
            if len(items) >= limit:
                break
        log.info(
            "drive folder %s: %d readable file(s), skipped %d unreadable",
            self._folder_id, len(items), skipped,
        )
        return items

    def fetch_content(self, item: RawItem, *, use_cache: bool = True) -> tuple[str | None, str]:
        settings.ensure_dirs()
        cache = _cache_path(item.url)
        if use_cache and cache.exists():
            data = json.loads(cache.read_text("utf-8"))
            return data.get("title"), data.get("text", "")
        service = self._build_service()
        file_id = item.meta.get("file_id")
        mime = item.meta.get("mime_type", "")
        if not file_id:
            return item.title, ""
        if mime in _EXPORT_MIME:
            raw: bytes = service.files().export(fileId=file_id, mimeType=_EXPORT_MIME[mime]).execute()
        else:
            raw = service.files().get_media(fileId=file_id, supportsAllDrives=True).execute()
        text = _bytes_to_text(raw, mime)
        cache.write_text(json.dumps({"url": item.url, "title": item.title, "text": text}), "utf-8")
        return item.title, text

    @staticmethod
    def _view_url(file_id: str, mime: str) -> str:
        if mime == "application/vnd.google-apps.document":
            return f"https://docs.google.com/document/d/{file_id}/edit"
        if mime == "application/vnd.google-apps.spreadsheet":
            return f"https://docs.google.com/spreadsheets/d/{file_id}/edit"
        if mime == "application/vnd.google-apps.presentation":
            return f"https://docs.google.com/presentation/d/{file_id}/edit"
        return f"https://drive.google.com/file/d/{file_id}/view"


def _bytes_to_text(raw: bytes, mime: str) -> str:
    if mime == "text/html":
        return html_to_text(raw.decode("utf-8", errors="replace"))
    if mime == "application/pdf":
        return _pdf_bytes_to_text(raw)
    return raw.decode("utf-8", errors="replace")


def _pdf_bytes_to_text(raw: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError:  # pragma: no cover
        log.warning("pypdf not installed — can't read PDF content; install sf-cert-crawler[internal]")
        return ""
    try:
        reader = PdfReader(io.BytesIO(raw))
        return "\n".join((page.extract_text() or "") for page in reader.pages)
    except Exception as exc:  # noqa: BLE001
        log.warning("failed to read PDF: %s", exc)
        return ""
