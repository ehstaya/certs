"""Shared CLI machinery for the two agents (`crawler.external`, `crawler.internal`).

Each agent module defines its own typer commands and source registry; the heavy
lifting (discover -> fetch -> filter -> extract -> dedup -> store, plus list /
runs / push) lives here so the two agents stay in sync.
"""

from __future__ import annotations

import contextlib
import json
from urllib.parse import urlparse

import typer

from . import audit, exams
from .config import settings
from .fetch import Fetcher, RobotsDisallowed
from .llm import LLM, BudgetExceeded, CostMeter, LLMUnavailable
from .logging_setup import get_logger, setup_logging
from .models import Question, RawItem
from .pipeline import classify, dedup, extract, quality, store
from .pipeline.filter_paid import check as filter_check
from .quizapp import QuizAppClient, QuizAppError
from .sources.base import Source

log = get_logger(__name__)

SUPPORTED_CERTS = {"admin"}


# --- LLM / embedder helpers -----------------------------------------------------


def make_llm(*, use_llm: bool, budget: float, allow_spend: bool) -> LLM | None:
    if not use_llm:
        return None
    meter = CostMeter(budget_usd=budget, allow_overspend=allow_spend)
    try:
        return LLM(meter=meter)
    except LLMUnavailable as exc:
        raise typer.BadParameter(f"{exc}. Set ANTHROPIC_API_KEY in .env, or run without --use-llm.")


def make_embedder(*, semantic_dedup: bool):  # -> dedup.Embedder | None
    if not semantic_dedup:
        return None
    try:
        return dedup.default_embedder()
    except dedup.SemanticDedupUnavailable as exc:
        raise typer.BadParameter(str(exc))


def _llm_stats(llm: LLM | None) -> tuple[int, float]:
    return (llm.meter.calls, llm.meter.spent_usd) if llm is not None else (0, 0.0)


def _build_source(source_registry: dict[str, type[Source]], name: str) -> Source:
    if name not in source_registry:
        raise typer.BadParameter(f"unknown source {name!r}; choices: {', '.join(source_registry)}")
    try:
        return source_registry[name]()
    except Exception as exc:  # noqa: BLE001 — instantiation failures are config errors
        raise typer.BadParameter(f"can't use source {name!r}: {exc}")


# --- per-document processing ----------------------------------------------------


class Counters:
    def __init__(self) -> None:
        self.deterministic = 0
        self.llm_extracted = 0
        self.classified_none = 0
        self.llm_dump = 0
        self.llm_suspicious = 0


def questions_for_kept_doc(
    *,
    src: Source,
    item: RawItem,
    html: str | None,
    title: str | None,
    text: str,
    cert: str,
    llm: LLM | None,
    counters: Counters,
    source_name: str,
) -> list[Question]:
    """Deterministic extraction first (HTML-only); fall back to the LLM pipeline.

    Image inputs (screenshots from the local-folder source) skip the text
    classify+dump-check entirely and go straight to Claude vision extraction —
    the classifier is text-based and can't see images, and image-bearing
    sources are admin-curated (trust_source=True) so the dump-check would be
    skipped anyway.
    """
    deterministic = src.extract_questions(html or "", item, cert=cert)
    if deterministic:
        counters.deterministic += 1
        return deterministic
    if llm is None:
        return []

    image_payload = src.fetch_image(item)
    if image_payload is not None:
        image_bytes, image_mime = image_payload
        log.info("%s -> Claude vision extract (image, %s, %d bytes)", item.url, image_mime, len(image_bytes))
        questions = extract.extract_questions_from_image(
            llm,
            title=title or item.title or "",
            image_bytes=image_bytes,
            image_mime=image_mime,
            source_url=item.url,
            source_type=src.source_type,
            source_license_note=getattr(src, "license_note", ""),
            cert=cert,
        )
        if questions:
            counters.llm_extracted += 1
            log.info("%s -> vision extracted %d question(s)", item.url, len(questions))
        return questions

    check = classify.has_study_questions(llm, title=title or "", text=text)
    if not check.has_questions:
        counters.classified_none += 1
        log.info("%s -> classify: no study questions (%s)", item.url, check.reason)
        return []

    if not getattr(src, "trust_source", False):
        verdict = classify.dump_check(llm, title=title or "", text=text)
        if verdict.decision == "dump":
            counters.llm_dump += 1
            audit.log_filtered(source=source_name, url=item.url, reason="LLM dump-check: dump", rules=["llm:dump"])
            log.info("%s -> LLM dump-check: dump (dropped)", item.url)
            return []
        if verdict.decision == "suspicious":
            counters.llm_suspicious += 1
            audit.log_review(source=source_name, url=item.url, reason="LLM dump-check: suspicious", rules=["llm:suspicious"])
            log.info("%s -> LLM dump-check: suspicious (review queue, not ingested)", item.url)
            return []
    else:
        log.info("%s -> trusted source (%s); skipping LLM dump-check", item.url, src.name)

    questions = extract.extract_questions(
        llm,
        title=title or "",
        text=text,
        source_url=item.url,
        source_type=src.source_type,
        source_license_note=getattr(src, "license_note", ""),
        cert=cert,
    )
    if questions:
        counters.llm_extracted += 1
        log.info("%s -> LLM extracted %d question(s)", item.url, len(questions))
    return questions


# --- `run` ----------------------------------------------------------------------


def do_run(
    *,
    agent_name: str,
    source_registry: dict[str, type[Source]],
    cert: str,
    source: str,
    limit: int,
    use_llm: bool,
    budget: float,
    allow_spend: bool,
    semantic_dedup: bool,
    similarity: float,
    no_cache: bool,
    quiet: bool,
    verbose: bool,
    supported_certs: set[str],
) -> None:
    setup_logging(verbose=verbose, quiet=quiet)
    if cert not in supported_certs:
        raise typer.BadParameter(f"unsupported --cert {cert!r}. Supported: {', '.join(sorted(supported_certs))}")
    src = _build_source(source_registry, source)
    use_cache = not no_cache
    llm = make_llm(use_llm=use_llm, budget=budget, allow_spend=allow_spend)
    embedder = make_embedder(semantic_dedup=semantic_dedup)
    run_id = store.start_run(agent=agent_name, mode="run", source=source, cert=cert)

    kept: list[tuple[str, str]] = []
    flagged: list[tuple[str, str]] = []
    dropped: list[tuple[str, str]] = []
    questions: list[Question] = []
    counters = Counters()
    items: list[RawItem] = []
    fetched = 0
    budget_hit = False
    status = "ok"
    note: str | None = None
    needs_fetcher = src.fetch_via_http

    try:
        with (Fetcher() if needs_fetcher else contextlib.nullcontext()) as fetcher:
            if needs_fetcher and fetcher is not None:
                fetcher.throttle.set_rate(urlparse(src.base_url).netloc.lower(), src.rate_per_sec)
            items = src.discover(fetcher, limit=limit, use_cache=use_cache)
            log.info("discovered %d candidate document(s) from %s", len(items), source)
            if not items:
                log.warning("no candidates discovered from %s — check source config / discover heuristics", source)

            for item in items:
                try:
                    if needs_fetcher and fetcher is not None:
                        doc = fetcher.fetch(item.url, use_cache=use_cache)
                        title, text = src.parse(doc.text, item)
                        html: str | None = doc.text
                    else:
                        title, text = src.fetch_content(item, use_cache=use_cache)
                        html = None
                    fetched += 1
                except RobotsDisallowed:
                    dropped.append((item.url, "robots.txt disallows fetch"))
                    continue
                except Exception as exc:  # noqa: BLE001 — report and keep going
                    dropped.append((item.url, f"fetch error: {exc!r}"))
                    continue

                result = filter_check(url=item.url, title=title or "", body=text)
                rules = ", ".join(result.matched_rules) or "-"
                detail = f"{result.reason} [{rules}]"
                if result.decision == "drop":
                    audit.log_filtered(source=source, url=item.url, reason=result.reason, rules=result.matched_rules)
                    dropped.append((item.url, detail))
                    continue
                if result.decision == "review":
                    audit.log_review(source=source, url=item.url, reason=result.reason, rules=result.matched_rules)
                    flagged.append((item.url, detail))
                    continue
                kept.append((item.url, title or "(untitled)"))
                try:
                    questions.extend(
                        questions_for_kept_doc(
                            src=src, item=item, html=html, title=title, text=text, cert=cert,
                            llm=llm, counters=counters, source_name=source,
                        )
                    )
                except BudgetExceeded as exc:
                    budget_hit, status, note = True, "budget", str(exc)
                    log.warning("stopping LLM processing: %s", exc)
                    break

        ins = store.insert_questions(questions, embedder=embedder, similarity_threshold=similarity) if questions else store.InsertResult()
    except Exception as exc:  # noqa: BLE001 — record the failure, then re-raise
        llm_calls, llm_cost = _llm_stats(llm)
        store.finish_run(
            run_id, status="error", note=repr(exc), discovered=len(items), fetched=fetched,
            filtered_dropped=len(dropped), filtered_review=len(flagged), kept=len(kept),
            extracted=len(questions), llm_calls=llm_calls, llm_cost_usd=llm_cost,
        )
        raise

    llm_calls, llm_cost = _llm_stats(llm)
    store.finish_run(
        run_id, status=status, note=note, discovered=len(items), fetched=fetched,
        filtered_dropped=len(dropped), filtered_review=len(flagged), kept=len(kept),
        extracted=len(questions), inserted=ins.inserted, skipped_exact=ins.skipped_exact,
        skipped_near=ins.skipped_near, llm_calls=llm_calls, llm_cost_usd=llm_cost,
    )

    if quiet:
        typer.echo(
            f"run #{run_id} [{agent_name}] {source} cert={cert}: discovered={len(items)} kept={len(kept)} "
            f"extracted={len(questions)} inserted={ins.inserted} exact_dup={ins.skipped_exact} near_dup={ins.skipped_near} "
            f"llm_calls={llm_calls} llm_cost=${llm_cost:.4f} status={status}"
        )
        return
    _report_run(
        run_id=run_id, agent_name=agent_name, source=source, cert=cert, kept=kept, flagged=flagged, dropped=dropped,
        counters=counters, extracted=len(questions), ins=ins, llm=llm, budget_hit=budget_hit, semantic=bool(embedder),
    )


# --- `ingest` (HTTP sources only) -----------------------------------------------


def do_ingest(
    *,
    agent_name: str,
    source_registry: dict[str, type[Source]],
    url: str,
    cert: str,
    use_llm: bool,
    budget: float,
    allow_spend: bool,
    semantic_dedup: bool,
    similarity: float,
    no_cache: bool,
    force: bool,
    verbose: bool,
    supported_certs: set[str],
) -> None:
    setup_logging(verbose=verbose)
    if cert not in supported_certs:
        raise typer.BadParameter(f"unsupported --cert {cert!r}. Supported: {', '.join(sorted(supported_certs))}")
    source = _source_for_url(source_registry, url)
    if source is None:
        raise typer.BadParameter(f"no source knows how to parse {url!r}. Known hosts: {', '.join(_known_hosts(source_registry)) or '(none — this agent has no HTTP sources)'}")
    src = _build_source(source_registry, source)
    if not src.fetch_via_http:
        raise typer.BadParameter("`ingest` is for web-URL sources only")
    item = RawItem(source_name=src.name, source_type=src.source_type, url=url)
    llm = make_llm(use_llm=use_llm, budget=budget, allow_spend=allow_spend)
    embedder = make_embedder(semantic_dedup=semantic_dedup)
    counters = Counters()
    run_id = store.start_run(agent=agent_name, mode="ingest", source=source, cert=cert)

    with Fetcher() as fetcher:
        fetcher.throttle.set_rate(urlparse(src.base_url).netloc.lower(), src.rate_per_sec)
        try:
            doc = fetcher.fetch(url, use_cache=not no_cache)
        except RobotsDisallowed:
            store.finish_run(run_id, status="error", note="robots.txt disallows")
            typer.echo(f"robots.txt disallows fetching {url} — aborting.")
            raise typer.Exit(code=1)
        title, text = src.parse(doc.text, item)
        result = filter_check(url=url, title=title or "", body=text)
        typer.echo(f"filter: {result.decision} — {result.reason} [{', '.join(result.matched_rules) or '-'}]")
        if result.decision == "drop":
            audit.log_filtered(source=source, url=url, reason=result.reason, rules=result.matched_rules)
            if not force:
                store.finish_run(run_id, status="ok", note="filter:drop (aborted, no --force)", discovered=1, fetched=1)
                typer.echo("aborting (pass --force to ingest anyway).")
                raise typer.Exit(code=1)
            typer.echo("--force given: ingesting despite the 'drop' verdict.")
        elif result.decision == "review":
            audit.log_review(source=source, url=url, reason=result.reason, rules=result.matched_rules)
            typer.echo("(flagged for review — proceeding because you asked for this URL specifically.)")
        try:
            questions = questions_for_kept_doc(
                src=src, item=item, html=doc.text, title=title, text=text, cert=cert,
                llm=llm, counters=counters, source_name=source,
            )
            status, note = "ok", None
        except BudgetExceeded as exc:
            questions, status, note = [], "budget", str(exc)
            typer.echo(f"stopped: {exc}")

    ins = store.insert_questions(questions, embedder=embedder, similarity_threshold=similarity) if questions else store.InsertResult()
    llm_calls, llm_cost = _llm_stats(llm)
    store.finish_run(
        run_id, status=status, note=note, discovered=1, fetched=1, kept=1, extracted=len(questions),
        inserted=ins.inserted, skipped_exact=ins.skipped_exact, skipped_near=ins.skipped_near,
        llm_calls=llm_calls, llm_cost_usd=llm_cost,
    )
    typer.echo(f"extracted {len(questions)} question(s) from {url}")
    if llm is not None:
        typer.echo(f"LLM: {llm.meter.summary()}")
    typer.echo(f"stored: {ins.inserted} new, {ins.skipped_exact} exact-duplicate(s), {ins.skipped_near} near-duplicate(s) skipped")
    _print_near_dups(ins.near_dups)
    if ins.inserted:
        _show_questions(store.recent_questions(cert=cert, limit=min(5, ins.inserted)), full=2)
    typer.echo(f"\ntotal questions in DB for cert={cert}: {store.count_questions(cert=cert)}  (run #{run_id})")
    typer.echo("not yet pushed to the quiz app — run `push` when ready.")


# --- `push` ---------------------------------------------------------------------


def do_push(*, agent_name: str, cert: str, limit: int | None, dry_run: bool, verbose: bool) -> None:
    setup_logging(verbose=verbose)
    cert_filter = None if cert == "all" else cert
    rows = store.unpushed_questions(cert=cert_filter, limit=limit)
    if not rows:
        typer.echo("nothing new to push (all stored questions already pushed, or none stored).")
        return
    ready: list[tuple[store.QuestionRow, quality.QualityResult]] = []
    rejected: list[tuple[store.QuestionRow, quality.QualityResult]] = []
    for row in rows:
        qr = quality.check_quality(row)
        store.record_quality(row.id, ok=qr.ok, issues=qr.issues, question_type=qr.question_type)
        (ready if qr.ok else rejected).append((row, qr))
    typer.echo(f"unpushed: {len(rows)}  |  pass quality gate: {len(ready)}  |  fail: {len(rejected)}")
    for row, qr in rejected:
        typer.echo(f"  [skip] {row.id}  {row.question_text[:60]}…  -> {'; '.join(qr.issues)}")
    if not ready:
        typer.echo("nothing passes the quality gate; nothing pushed.")
        return

    # The import API is exam-scoped: one envelope per exam. Group the ready
    # questions by their cert, then map each cert -> its exam envelope.
    by_cert: dict[str, list[tuple[store.QuestionRow, quality.QualityResult]]] = {}
    for row, qr in ready:
        by_cert.setdefault(row.cert, []).append((row, qr))
    try:
        envelopes = {
            c: exams.build_envelope(c, [_build_quiz_payload(r, q) for r, q in items])
            for c, items in by_cert.items()
        }
    except exams.UnknownCert as exc:
        raise typer.BadParameter(str(exc))

    if dry_run:
        typer.echo(
            f"--dry-run: would POST {len(by_cert)} exam envelope(s) to "
            f"{settings.quiz_app_url}/admin/questions/import; NOT sending, NOT marking pushed.\n"
        )
        for c, env in envelopes.items():
            typer.echo(f"=== cert={c} -> exam '{env['slug']}'  ({len(env['questions'])} question(s)) ===")
            typer.echo(json.dumps(env, indent=2, ensure_ascii=False))
        return

    run_id = store.start_run(agent=agent_name, mode="push", source=None, cert=cert)
    total_imported = total_skipped = 0
    pushed_count = 0
    try:
        with QuizAppClient() as client:
            client.login()
            for c, env in envelopes.items():
                res = client.import_exam(env)
                store.mark_pushed([r.id for r, _ in by_cert[c]])
                total_imported += res.imported
                total_skipped += res.skipped
                pushed_count += len(env["questions"])
                typer.echo(
                    f"exam '{res.exam}': pushed {len(env['questions'])} -> "
                    f"imported {res.imported}, skipped {res.skipped} (already present)"
                )
                for st in res.skipped_texts[:8]:
                    typer.echo(f"    - {st}")
    except QuizAppError as exc:
        store.finish_run(run_id, status="error", note=str(exc))
        raise typer.BadParameter(str(exc))
    store.finish_run(
        run_id, status="ok", pushed=pushed_count, inserted=total_imported,
        skipped_exact=total_skipped, kept=len(ready),
    )
    typer.echo(
        f"\ntotal: imported {total_imported}, skipped {total_skipped} across {len(by_cert)} exam(s). "
        f"Approve at {settings.quiz_app_url}/admin/questions   (run #{run_id})"
    )


def _build_quiz_payload(row: store.QuestionRow, qr: quality.QualityResult) -> dict:
    correct = set(qr.correct_options)
    options = [o.strip() for o in (row.options or []) if o and o.strip()]
    choices = [
        {"label": chr(ord("A") + i), "text": opt, "correct": opt in correct}
        for i, opt in enumerate(options)
    ]
    return {
        "type": qr.question_type,
        "text": row.question_text,
        "helpUrl": "",  # admin can add a Salesforce help-doc link in the review UI
        "sourceUrl": row.source_url or "",
        "explanation": row.explanation or "",
        "choices": choices,
    }


# --- `list` / `runs` ------------------------------------------------------------


def do_list(*, cert: str, limit: int, full: int) -> None:
    setup_logging()
    rows = store.recent_questions(cert=cert, limit=limit)
    if not rows:
        typer.echo(f"no questions stored for cert={cert} yet.")
        return
    _show_questions(rows, full=full)
    typer.echo(f"\ntotal questions in DB for cert={cert}: {store.count_questions(cert=cert)}")


def do_runs(*, limit: int) -> None:
    setup_logging()
    rows = store.recent_runs(limit=limit)
    if not rows:
        typer.echo("no runs recorded yet.")
        return
    typer.echo(
        f"{'id':>4}  {'started (UTC)':<19}  {'agent':<8} {'mode':<7} {'source':<13} {'cert':<6} "
        f"{'disc':>4} {'kept':>4} {'extr':>4} {'ins':>4} {'xdup':>4} {'ndup':>4} {'push':>4} {'llm$':>8}  status"
    )
    for r in rows:
        started = r.started_at.strftime("%Y-%m-%d %H:%M:%S") if r.started_at else "-"
        note = f"  ({r.note})" if r.note else ""
        typer.echo(
            f"{r.id:>4}  {started:<19}  {(r.agent or '-'):<8} {r.mode:<7} {(r.source or '-'):<13} {r.cert:<6} "
            f"{r.discovered:>4} {r.kept:>4} {r.extracted:>4} {r.inserted:>4} {r.skipped_exact:>4} "
            f"{r.skipped_near:>4} {r.pushed:>4} {r.llm_cost_usd:>8.4f}  {r.status}{note}"
        )


# --- helpers --------------------------------------------------------------------


def _known_hosts(source_registry: dict[str, type[Source]]) -> list[str]:
    return [urlparse(cls.base_url).netloc.lower() for cls in source_registry.values() if cls.base_url]


def _source_for_url(source_registry: dict[str, type[Source]], url: str) -> str | None:
    host = urlparse(url).netloc.lower().removeprefix("www.")
    for name, cls in source_registry.items():
        if cls.base_url and urlparse(cls.base_url).netloc.lower().removeprefix("www.") == host:
            return name
    return None


def _report_run(
    *, run_id: int, agent_name: str, source: str, cert: str,
    kept: list[tuple[str, str]], flagged: list[tuple[str, str]], dropped: list[tuple[str, str]],
    counters: Counters, extracted: int, ins: store.InsertResult, llm: LLM | None, budget_hit: bool, semantic: bool,
) -> None:
    echo = typer.echo
    echo("")
    echo(f"=== {agent_name} agent | {source} | cert={cert} | run #{run_id} ===\n")
    echo(f"KEPT ({len(kept)}):")
    for url, title in kept:
        echo(f"  [+] {title}")
        echo(f"      {url}")
    echo(f"\nFLAGGED FOR REVIEW ({len(flagged)})  -> logs/review.jsonl")
    for url, detail in flagged:
        echo(f"  [?] {url}")
        echo(f"      {detail}")
    echo(f"\nDROPPED ({len(dropped)})  -> logs/filtered.jsonl")
    for url, detail in dropped:
        echo(f"  [-] {url}")
        echo(f"      {detail}")
    echo("")
    echo(f"filter totals: kept={len(kept)}  review={len(flagged)}  dropped={len(dropped)}")
    if llm is not None:
        echo(
            "LLM pipeline: "
            f"deterministic_docs={counters.deterministic}  llm_extracted_docs={counters.llm_extracted}  "
            f"classified_none={counters.classified_none}  llm_dump={counters.llm_dump}  llm_suspicious={counters.llm_suspicious}"
        )
        echo(f"LLM cost: {llm.meter.summary()}")
        if budget_hit:
            echo("note: stopped early — LLM budget exceeded; rerun with --allow-spend or a higher --budget.")
    dedup_note = "semantic+exact" if semantic else "exact-hash only"
    echo(f"questions: extracted={extracted}  inserted={ins.inserted}  exact_dup_skipped={ins.skipped_exact}  near_dup_skipped={ins.skipped_near}  (dedup: {dedup_note})")
    _print_near_dups(ins.near_dups)
    echo(f"DB total for cert={cert}: {store.count_questions(cert=cert)}  (stored, not yet pushed to the quiz app — run `push`)")
    echo("see `runs` for run history; raw fetches cached under ./cache/")


def _print_near_dups(near_dups: list[dedup.NearDup]) -> None:
    if not near_dups:
        return
    typer.echo("near-duplicates skipped:")
    for nd in near_dups:
        snippet = nd.question.question_text[:70] + ("…" if len(nd.question.question_text) > 70 else "")
        typer.echo(f"  ~ sim={nd.similarity:.3f} matches stored id {nd.matched_id}: {snippet}")


def _show_questions(rows: list[store.QuestionRow], *, full: int = 0) -> None:
    typer.echo("\nrows (most recent first):")
    typer.echo(f"  {'id':<16}  {'cert':<8}  {'#opt':>4}  {'ans?':<4}  {'qual':<5}  {'pushed':<6}  source")
    for row in rows:
        n_opts = len(row.options) if row.options else 0
        qual = "ok" if row.quality_ok else ("no" if row.quality_ok is False else "-")
        pushed = "yes" if row.pushed_at else "no"
        typer.echo(
            f"  {row.id:<16}  {row.cert:<8}  {n_opts:>4}  {'yes' if row.correct_answer else 'no':<4}  "
            f"{qual:<5}  {pushed:<6}  {row.source_url}"
        )
    for row in rows[: max(0, full)]:
        typer.echo("\n  ---")
        typer.echo(
            f"  id: {row.id}  cert: {row.cert}  topic: {row.topic or '-'}  type: {row.question_type or '?'}  "
            f"conf: {row.confidence:.2f}  pushed: {'yes' if row.pushed_at else 'no'}"
        )
        if row.quality_ok is False and row.quality_issues:
            typer.echo(f"  quality issues: {'; '.join(row.quality_issues)}")
        typer.echo(f"  Q: {row.question_text}")
        for idx, opt in enumerate(row.options or [], start=1):
            mark = "  <- correct" if row.correct_answer and opt == row.correct_answer else ""
            typer.echo(f"     {idx}. {opt}{mark}")
        if row.correct_answer and row.correct_answer not in (row.options or []):
            typer.echo(f"     correct: {row.correct_answer}")
        if row.explanation:
            snippet = row.explanation if len(row.explanation) <= 400 else row.explanation[:400] + " ..."
            typer.echo(f"  explanation: {snippet}")
