from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """Process-wide configuration.

    Reads `.env` at the repo root; every field can also be overridden via an
    uppercased environment variable (e.g. ``DEFAULT_RATE_PER_SEC=0.5``).
    """

    model_config = SettingsConfigDict(
        env_file=REPO_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    user_agent: str = "SalesforceStudyBot/0.1 (personal-study; contact: ehstaya@gmail.com)"
    default_rate_per_sec: float = 1.0
    request_timeout_s: float = 20.0
    max_retries: int = 4

    cache_dir: Path = REPO_ROOT / "cache"
    logs_dir: Path = REPO_ROOT / "logs"
    db_path: Path = REPO_ROOT / "questions.db"

    # Anthropic Claude — used by the LLM classify/extract pipeline (both agents).
    anthropic_api_key: str | None = None

    # --- internal agent: Google Drive source ---------------------------------
    # Provide ONE of: a service-account JSON file, or an OAuth user-token JSON.
    google_service_account_file: Path | None = None
    google_oauth_token_file: Path | None = None
    # The Drive folder to read from (just the folder id, not a URL).
    google_drive_folder_id: str | None = None

    # --- internal agent: local-folder source ---------------------------------
    local_source_dir: Path | None = None

    # --- quiz app (the "Salesforce Admin Practice playground") ---------------
    quiz_app_url: str = "http://localhost:8095"
    quiz_app_admin_email: str | None = None
    quiz_app_admin_password: str | None = None

    # --- automated quality gate (before a question is pushed to the quiz app) -
    quality_min_confidence: float = 0.5

    def ensure_dirs(self) -> None:
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)


settings = Settings()
