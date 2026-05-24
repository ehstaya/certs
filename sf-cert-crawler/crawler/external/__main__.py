"""External agent — crawls public, free Salesforce-cert blogs (no internal access).

  python -m crawler.external run --cert admin [--use-llm] [--semantic-dedup]
  python -m crawler.external ingest <url>
  python -m crawler.external push          # send quality-passing questions to the quiz app
  python -m crawler.external list | runs
"""

from __future__ import annotations

import typer

from .. import cli_common
from ..sources.apexhours import ApexHoursSource
from ..sources.base import Source
from ..sources.salesforceben import SalesforceBenSource

AGENT = "external"
SOURCES: dict[str, type[Source]] = {
    "salesforceben": SalesforceBenSource,
    "apexhours": ApexHoursSource,
}

app = typer.Typer(add_completion=False, help="External agent: crawls public Salesforce-cert blogs.")


@app.callback()
def _cli() -> None:
    """External agent — public web crawling only (Salesforce Ben, Apex Hours). No internal/Google access."""


@app.command()
def run(
    cert: str = typer.Option("admin", help="Target certification (only 'admin')."),
    source: str = typer.Option("salesforceben", help=f"Source: {', '.join(SOURCES)}."),
    limit: int = typer.Option(20, min=1, help="Max candidate documents to discover."),
    use_llm: bool = typer.Option(False, "--use-llm", help="Run the Claude classify+extract steps on docs without quiz markup."),
    budget: float = typer.Option(1.0, min=0.0, help="Per-run LLM spend cap (USD)."),
    allow_spend: bool = typer.Option(False, "--allow-spend", help="Allow LLM spend to exceed --budget."),
    semantic_dedup: bool = typer.Option(False, "--semantic-dedup", help="Skip near-duplicate questions (local embeddings)."),
    similarity: float = typer.Option(0.92, min=0.0, max=1.0, help="Near-duplicate cosine threshold."),
    no_cache: bool = typer.Option(False, "--no-cache", help="Bypass the on-disk fetch cache."),
    quiet: bool = typer.Option(False, "--quiet", help="Cron-friendly: warnings only, one-line summary."),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Debug logging."),
) -> None:
    """Discover -> fetch -> filter -> extract -> dedup -> store. Questions land in the local DB; run `push` to send them on."""
    cli_common.do_run(
        agent_name=AGENT, source_registry=SOURCES, cert=cert, source=source, limit=limit,
        use_llm=use_llm, budget=budget, allow_spend=allow_spend, semantic_dedup=semantic_dedup,
        similarity=similarity, no_cache=no_cache, quiet=quiet, verbose=verbose, supported_certs=cli_common.SUPPORTED_CERTS,
    )


@app.command()
def ingest(
    url: str = typer.Argument(..., help="Article URL to scrape for structured questions."),
    cert: str = typer.Option("admin", help="Certification to tag extracted questions with."),
    use_llm: bool = typer.Option(False, "--use-llm", help="If there's no quiz markup, use the Claude pipeline."),
    budget: float = typer.Option(1.0, min=0.0, help="LLM spend cap (USD) for this ingest."),
    allow_spend: bool = typer.Option(False, "--allow-spend", help="Allow LLM spend to exceed --budget."),
    semantic_dedup: bool = typer.Option(False, "--semantic-dedup", help="Skip near-duplicate questions."),
    similarity: float = typer.Option(0.92, min=0.0, max=1.0, help="Near-duplicate cosine threshold."),
    no_cache: bool = typer.Option(False, "--no-cache", help="Bypass the on-disk fetch cache."),
    force: bool = typer.Option(False, "--force", help="Ingest even if the paid/dump filter says 'drop'."),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Debug logging."),
) -> None:
    """Scrape one specific URL: fetch -> filter -> extract -> dedup -> store."""
    cli_common.do_ingest(
        agent_name=AGENT, source_registry=SOURCES, url=url, cert=cert, use_llm=use_llm, budget=budget,
        allow_spend=allow_spend, semantic_dedup=semantic_dedup, similarity=similarity, no_cache=no_cache,
        force=force, verbose=verbose, supported_certs=cli_common.SUPPORTED_CERTS,
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
    """Show recent crawl/ingest/push runs."""
    cli_common.do_runs(limit=limit)


if __name__ == "__main__":
    app()
