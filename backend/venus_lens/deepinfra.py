"""Thin async client for DeepInfra's OpenAI-compatible API (chat stream + STT)."""
from __future__ import annotations

import json
import time
from typing import AsyncIterator

import httpx

CATALOG_URL = "https://api.deepinfra.com/models/list"


class DeepInfraError(RuntimeError):
    pass


class DeepInfra:
    def __init__(self, key: str, base_url: str, transport: httpx.AsyncBaseTransport | None = None,
                 timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(
            headers={"Authorization": f"Bearer {key}"},
            timeout=httpx.Timeout(timeout, connect=10.0),
            transport=transport,
        )
        self._prices: dict[str, dict] | None = None
        self._prices_at = 0.0

    async def aclose(self) -> None:
        await self._client.aclose()

    async def chat_stream(self, model: str, messages: list[dict], max_tokens: int,
                          disable_reasoning: bool = True) -> AsyncIterator[tuple[str, object]]:
        """Yield ("delta", str) chunks, then ("usage", dict) if the server sends it."""
        body = {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": 0.2,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if disable_reasoning:
            body["reasoning_effort"] = "none"
        async with self._client.stream("POST", f"{self.base_url}/chat/completions", json=body) as r:
            if r.status_code >= 400:
                detail = (await r.aread()).decode(errors="replace")[:300]
                raise DeepInfraError(f"chat {r.status_code}: {detail}")
            async for line in r.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                except json.JSONDecodeError:
                    continue
                for choice in chunk.get("choices") or []:
                    text = (choice.get("delta") or {}).get("content")
                    if text:
                        yield "delta", text
                if chunk.get("usage"):
                    yield "usage", chunk["usage"]

    async def transcribe(self, audio: bytes, filename: str, model: str, language: str) -> str:
        files = {"file": (filename, audio)}
        data = {"model": model, "language": language}
        r = await self._client.post(f"{self.base_url}/audio/transcriptions", files=files, data=data)
        if r.status_code >= 400:
            raise DeepInfraError(f"stt {r.status_code}: {r.text[:300]}")
        return (r.json().get("text") or "").strip()

    async def prices(self) -> dict[str, dict]:
        """Catalog pricing, cached 6h. Empty dict on failure: cost is informational only."""
        if self._prices is not None and time.time() - self._prices_at < 6 * 3600:
            return self._prices
        try:
            r = await self._client.get(CATALOG_URL)
            r.raise_for_status()
            self._prices = {m["model_name"]: m.get("pricing") or {} for m in r.json()}
            self._prices_at = time.time()
        except Exception:
            self._prices = self._prices or {}
        return self._prices

    async def cost_usd(self, model: str, usage: dict | None) -> float | None:
        if not usage:
            return None
        if isinstance(usage.get("estimated_cost"), (int, float)):  # DeepInfra reports USD per call
            return round(float(usage["estimated_cost"]), 8)
        p = (await self.prices()).get(model) or {}
        cin, cout = p.get("cents_per_input_token"), p.get("cents_per_output_token")
        if cin is None or cout is None:
            return None
        cents = usage.get("prompt_tokens", 0) * cin + usage.get("completion_tokens", 0) * cout
        return round(cents / 100, 8)
