from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from typing import Any

from .config import settings
from .logging_setup import get_logger

log = get_logger(__name__)

# Model IDs (no date suffixes — these strings are complete as-is).
HAIKU = "claude-haiku-4-5"
SONNET = "claude-sonnet-4-6"

# USD per 1M tokens, (input, output). Cache writes are billed ~1.25x input and
# cache reads ~0.1x input; for a *budget cap* we deliberately fold every
# input-side token (fresh + cache write + cache read) into the full input price
# so the estimate errs high. Update if Anthropic pricing changes.
_PRICE_PER_MTOK: dict[str, tuple[float, float]] = {
    HAIKU: (1.00, 5.00),
    SONNET: (3.00, 15.00),
}

DEFAULT_RUN_BUDGET_USD = 1.00
_CHARS_PER_TOKEN = 4  # rough heuristic for the pre-call cost projection only


class BudgetExceeded(RuntimeError):
    """A planned or cumulative LLM spend would exceed the run budget."""


class LLMUnavailable(RuntimeError):
    """No Anthropic API key is configured."""


@dataclass
class CostMeter:
    """Tracks LLM token usage / USD spend for one run and enforces a budget."""

    budget_usd: float = DEFAULT_RUN_BUDGET_USD
    allow_overspend: bool = False
    calls: int = 0
    # model -> [input_side_tokens, output_tokens]; input_side folds in cache r/w.
    _usage: dict[str, list[int]] = field(default_factory=dict)

    def usage_for(self, model: str) -> tuple[int, int]:
        it, ot = self._usage.get(model, [0, 0])
        return it, ot

    @property
    def spent_usd(self) -> float:
        total = 0.0
        for model, (it, ot) in self._usage.items():
            pin, pout = _PRICE_PER_MTOK.get(model, (0.0, 0.0))
            total += it / 1_000_000 * pin + ot / 1_000_000 * pout
        return total

    def record(self, model: str, *, input_side_tokens: int, output_tokens: int) -> None:
        self.calls += 1
        cur = self._usage.setdefault(model, [0, 0])
        cur[0] += input_side_tokens
        cur[1] += output_tokens

    def estimate_call_usd(self, model: str, *, prompt_chars: int, max_output_tokens: int) -> float:
        pin, pout = _PRICE_PER_MTOK.get(model, (0.0, 0.0))
        est_in_tokens = prompt_chars / _CHARS_PER_TOKEN
        return est_in_tokens / 1_000_000 * pin + max_output_tokens / 1_000_000 * pout

    def guard(self, *, additional_usd: float = 0.0) -> None:
        if self.allow_overspend:
            return
        projected = self.spent_usd + additional_usd
        if projected > self.budget_usd:
            raise BudgetExceeded(
                f"projected LLM spend ${projected:.4f} would exceed the run budget "
                f"${self.budget_usd:.2f} — pass --allow-spend to override"
            )

    def summary(self) -> str:
        parts = [f"{m}: in~{it} out~{ot}" for m, (it, ot) in self._usage.items()]
        return f"{self.calls} LLM call(s); {'; '.join(parts) or 'no usage'}; ≈ ${self.spent_usd:.4f} of ${self.budget_usd:.2f} budget"


class LLM:
    """Thin wrapper over the Anthropic Messages API.

    Caches the (stable) system prompt with an ephemeral breakpoint, meters token
    usage/spend via a :class:`CostMeter`, and checks the run budget *before* each
    call using a conservative pre-estimate.

    Note: the current system prompts are short (well under the ~1–4K-token cache
    minimum), so the `cache_control` breakpoint is a no-op until they grow — it's
    the canonical pattern, costs nothing, and starts paying off automatically if
    the prompts get longer.
    """

    def __init__(self, *, meter: CostMeter, api_key: str | None = None, client: Any = None) -> None:
        self.meter = meter
        if client is not None:
            self._client = client
            return
        key = api_key or settings.anthropic_api_key or os.environ.get("ANTHROPIC_API_KEY")
        if not key:
            raise LLMUnavailable("ANTHROPIC_API_KEY is not set (put it in .env)")
        from anthropic import Anthropic  # lazy import: tests run with a fake client, no SDK needed

        self._client = Anthropic(api_key=key)

    def complete(self, *, model: str, system: str, user: str, max_tokens: int) -> str:
        pre_estimate = self.meter.estimate_call_usd(
            model, prompt_chars=len(system) + len(user), max_output_tokens=max_tokens
        )
        self.meter.guard(additional_usd=pre_estimate)
        resp = self._client.messages.create(
            model=model,
            max_tokens=max_tokens,
            system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": user}],
        )
        self._record_usage(resp, model)
        return "".join(block.text for block in resp.content if getattr(block, "type", None) == "text")

    def complete_with_image(
        self,
        *,
        model: str,
        system: str,
        user_text: str,
        image_bytes: bytes,
        image_mime: str,
        max_tokens: int,
    ) -> str:
        """Same as :meth:`complete` but the user message also carries one image
        (sent as a base64 ``image`` content block). Used by the vision extractor
        on uploaded screenshots."""
        import base64
        # Image tokens depend on resolution; without decoding the image we use
        # a conservative flat estimate (~1600 tokens) for the budget pre-check.
        # The actual count comes back in `response.usage.input_tokens`.
        image_token_estimate = 1600
        pre_estimate = self.meter.estimate_call_usd(
            model,
            prompt_chars=len(system) + len(user_text) + image_token_estimate * 4,
            max_output_tokens=max_tokens,
        )
        self.meter.guard(additional_usd=pre_estimate)
        encoded = base64.standard_b64encode(image_bytes).decode("ascii")
        resp = self._client.messages.create(
            model=model,
            max_tokens=max_tokens,
            system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": image_mime, "data": encoded}},
                    {"type": "text", "text": user_text},
                ],
            }],
        )
        self._record_usage(resp, model)
        return "".join(block.text for block in resp.content if getattr(block, "type", None) == "text")

    def _record_usage(self, resp: Any, model: str) -> None:
        usage = resp.usage
        input_side = (
            (getattr(usage, "input_tokens", 0) or 0)
            + (getattr(usage, "cache_creation_input_tokens", 0) or 0)
            + (getattr(usage, "cache_read_input_tokens", 0) or 0)
        )
        self.meter.record(
            model, input_side_tokens=input_side, output_tokens=(getattr(usage, "output_tokens", 0) or 0)
        )


# --- defensive JSON parsing for model replies -----------------------------------


def _strip_fences(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z0-9]*\n?", "", text)
        text = re.sub(r"\n?```$", "", text)
    return text.strip()


def parse_json_object(text: str) -> dict | None:
    cleaned = _strip_fences(text)
    try:
        value = json.loads(cleaned)
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        pass
    match = re.search(r"\{.*\}", cleaned, re.DOTALL)
    if not match:
        return None
    try:
        value = json.loads(match.group(0))
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        return None


def parse_json_array(text: str) -> list | None:
    cleaned = _strip_fences(text)
    try:
        value = json.loads(cleaned)
        return value if isinstance(value, list) else None
    except json.JSONDecodeError:
        pass
    match = re.search(r"\[.*\]", cleaned, re.DOTALL)
    if not match:
        return None
    try:
        value = json.loads(match.group(0))
        return value if isinstance(value, list) else None
    except json.JSONDecodeError:
        return None
