"""Dispatcher for the two agents.

Run an agent directly — `python -m crawler.external ...` / `python -m crawler.internal ...` —
or via this dispatcher: `python -m crawler external ...` / `python -m crawler internal ...`.

  external : crawls public, free Salesforce-cert blogs (no internal access)
  internal : reads Google Drive / a local folder, inside the SF network (no web crawling)
"""

from __future__ import annotations

import typer

from .external.__main__ import app as external_app
from .internal.__main__ import app as internal_app

app = typer.Typer(add_completion=False, help="Salesforce cert question crawler — two agents: external (web) and internal (Google Drive / local folder).")
app.add_typer(external_app, name="external")
app.add_typer(internal_app, name="internal")


@app.callback()
def _cli() -> None:
    """Pick a sub-agent: `external` (public web crawling) or `internal` (Google Drive / local folder)."""


if __name__ == "__main__":
    app()
