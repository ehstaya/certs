"""Internal agent — runs inside the Salesforce network.

Reads study material ONLY from Google Drive or a local folder; does NO web
crawling. May call the Anthropic API for extraction (per the agreed network
posture). Extracted questions go through a quality gate, then `push` sends them
to the quiz app where an admin approves them.

  python -m crawler.internal run --source drive --use-llm
  python -m crawler.internal run --source localfolder --use-llm
  python -m crawler.internal push
  python -m crawler.internal list | runs
  python -m crawler.internal watch              # long-running: auto-run+push on new uploads
"""

from __future__ import annotations

import time
from pathlib import Path

import typer

from .. import cli_common
from ..config import settings
from ..logging_setup import get_logger, setup_logging
from ..sources.base import Source
from ..sources.drive import DriveSource
from ..sources.localfolder import LocalFolderSource

AGENT = "internal"
SOURCES: dict[str, type[Source]] = {
    "drive": DriveSource,
    "localfolder": LocalFolderSource,
}

app = typer.Typer(add_completion=False, help="Internal agent: reads Google Drive / a local folder (no web crawling).")


@app.callback()
def _cli() -> None:
    """Internal agent — Google Drive / local-folder sources only. No web crawling. Configure GOOGLE_* / LOCAL_SOURCE_DIR + QUIZ_APP_* in .env."""


@app.command()
def run(
    cert: str = typer.Option("admin", help="Target certification (only 'admin')."),
    source: str = typer.Option("drive", help=f"Source: {', '.join(SOURCES)}."),
    limit: int = typer.Option(20, min=1, help="Max documents to read."),
    use_llm: bool = typer.Option(False, "--use-llm", help="Use the Claude classify+extract pipeline (you almost certainly want this for Drive/notes content)."),
    budget: float = typer.Option(1.0, min=0.0, help="Per-run LLM spend cap (USD)."),
    allow_spend: bool = typer.Option(False, "--allow-spend", help="Allow LLM spend to exceed --budget."),
    semantic_dedup: bool = typer.Option(False, "--semantic-dedup", help="Skip near-duplicate questions (local embeddings)."),
    similarity: float = typer.Option(0.92, min=0.0, max=1.0, help="Near-duplicate cosine threshold."),
    no_cache: bool = typer.Option(False, "--no-cache", help="Bypass the on-disk content cache."),
    quiet: bool = typer.Option(False, "--quiet", help="Cron-friendly: warnings only, one-line summary."),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Debug logging."),
) -> None:
    """Read internal docs -> filter -> extract -> dedup -> store. Questions land in the local DB; run `push` to send them to the quiz app."""
    cli_common.do_run(
        agent_name=AGENT, source_registry=SOURCES, cert=cert, source=source, limit=limit,
        use_llm=use_llm, budget=budget, allow_spend=allow_spend, semantic_dedup=semantic_dedup,
        similarity=similarity, no_cache=no_cache, quiet=quiet, verbose=verbose, supported_certs=cli_common.SUPPORTED_CERTS,
    )


@app.command()
def push(
    cert: str = typer.Option("all", help="Push only this cert's questions (or 'all')."),
    limit: int = typer.Option(0, min=0, help="Max questions to push this run (0 = no limit)."),
    dry_run: bool = typer.Option(False, "--dry-run", help="Show what would be pushed; don't send, don't mark pushed."),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Debug logging."),
) -> None:
    """Run the quality gate on un-pushed questions and POST the passing ones to the quiz app (they land as PENDING for admin approval)."""
    cli_common.do_push(agent_name=AGENT, cert=cert, limit=(limit or None), dry_run=dry_run, verbose=verbose)


@app.command()
def watch(
    interval: int = typer.Option(20, min=5, help="Poll interval in seconds."),
    use_llm: bool = typer.Option(True, "--use-llm/--no-use-llm", help="Use the Claude pipeline (default ON for the watcher)."),
    budget: float = typer.Option(1.0, min=0.0, help="LLM spend cap per pipeline cycle (USD)."),
    allow_spend: bool = typer.Option(False, "--allow-spend", help="Allow LLM spend to exceed --budget."),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Debug logging."),
) -> None:
    """Long-running: poll LOCAL_SOURCE_DIR; when a file appears or changes, auto-run the
    full pipeline (run + push) so new admin uploads in the GUI flow into the cert
    app's pending-review queue without a manual two-step.

    Polling is cheap (a dir listing); the LLM is invoked only when a new/changed
    file is detected, so an idle watcher costs ~$0 per cycle.
    """
    setup_logging(verbose=verbose)
    log = get_logger("internal.watch")
    src_dir = Path(settings.local_source_dir) if settings.local_source_dir else None
    if src_dir is None or not src_dir.is_dir():
        raise typer.BadParameter(f"LOCAL_SOURCE_DIR is not a directory: {src_dir}")
    if use_llm and not settings.anthropic_api_key:
        log.warning("--use-llm is on but ANTHROPIC_API_KEY is not set — pipeline cycles will fail until you set it.")
    if not (settings.quiz_app_admin_email and settings.quiz_app_admin_password):
        log.warning("QUIZ_APP_ADMIN_* not fully set — push will fail until you set them.")

    log.info("watching %s every %ds (Ctrl-C to stop)", src_dir, interval)
    seen = _snapshot(src_dir)
    log.info("initial: %d file(s) in %s", len(seen), src_dir)

    while True:
        try:
            time.sleep(interval)
        except KeyboardInterrupt:
            log.info("stopping watcher")
            return
        try:
            current = _snapshot(src_dir)
        except Exception as exc:  # noqa: BLE001
            log.warning("snapshot failed: %s", exc)
            continue
        if current == seen:
            continue
        new_or_changed = sorted(name for (name, _) in current - seen)
        log.info("detected %d new/changed file(s): %s", len(new_or_changed), new_or_changed)
        seen = current
        _run_pipeline_cycle(use_llm=use_llm, budget=budget, allow_spend=allow_spend, verbose=verbose, log=log)


def _snapshot(directory: Path) -> set[tuple[str, int]]:
    """Set of (name, mtime_ns) for regular files in `directory`."""
    return {(p.name, p.stat().st_mtime_ns) for p in directory.iterdir() if p.is_file()}


def _run_pipeline_cycle(*, use_llm: bool, budget: float, allow_spend: bool, verbose: bool, log) -> None:
    """One extraction+push cycle. Errors are logged; the watcher loop continues."""
    try:
        cli_common.do_run(
            agent_name=AGENT, source_registry=SOURCES, cert="admin", source="localfolder",
            limit=1000, use_llm=use_llm, budget=budget, allow_spend=allow_spend,
            semantic_dedup=False, similarity=0.92, no_cache=False,
            quiet=False, verbose=verbose, supported_certs=cli_common.SUPPORTED_CERTS,
        )
    except typer.Exit:
        pass  # do_run may raise typer.Exit on edge cases — keep watching
    except typer.BadParameter as exc:
        log.error("run failed: %s", getattr(exc, "message", exc))
        return  # don't push if run failed
    except Exception as exc:  # noqa: BLE001
        log.exception("run cycle failed: %s", exc)
        return
    try:
        cli_common.do_push(agent_name=AGENT, cert="all", limit=None, dry_run=False, verbose=verbose)
    except typer.Exit:
        pass
    except typer.BadParameter as exc:
        log.error("push failed: %s (the run's questions stay in the agent DB; next cycle will retry)", getattr(exc, "message", exc))
    except Exception as exc:  # noqa: BLE001
        log.exception("push cycle failed: %s", exc)


@app.command(name="list")
def list_questions(
    cert: str = typer.Option("admin", help="Filter by certification."),
    limit: int = typer.Option(10, min=1, help="How many recent rows to show."),
    full: int = typer.Option(0, min=0, help="Show full text + options for the first N rows."),
) -> None:
    """Show recently-stored question rows from the local DB."""
    cli_common.do_list(cert=cert, limit=limit, full=full)


@app.command()
def runs(limit: int = typer.Option(15, min=1, help="How many recent runs to show.")) -> None:
    """Show recent run/push history."""
    cli_common.do_runs(limit=limit)


if __name__ == "__main__":
    app()
