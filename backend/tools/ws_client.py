"""Phone simulator: sends a JPEG frame + a question (text or audio file) over the WebSocket.

  uv run python tools/ws_client.py --image foto.jpg --text "o que é isso?"
  uv run python tools/ws_client.py --image foto.jpg --audio pergunta.wav
Token comes from VENUS_LENS_TOKEN (environment or the repo .env).
"""
import argparse
import asyncio
import json
import os
import time
from pathlib import Path

import websockets


def token() -> str:
    from venus_lens.config import Settings
    t = Settings.from_env().api_token
    if not t:
        raise SystemExit("VENUS_LENS_TOKEN not set (environment or .env)")
    return t


async def main(a) -> None:
    async with websockets.connect(a.url, additional_headers={"X-API-Key": token()},
                                  max_size=8 * 1024 * 1024) as ws:
        print("<", await ws.recv())
        if a.image:
            await ws.send(b"\x01" + Path(a.image).read_bytes())
        t0 = time.perf_counter()
        if a.audio:
            await ws.send(b"\x02" + Path(a.audio).read_bytes())
        else:
            await ws.send(json.dumps({"type": "ask", "text": a.text, "mode": a.mode, "id": "cli"}))
        first = None
        while True:
            m = json.loads(await ws.recv())
            if m["type"] == "delta":
                if first is None:
                    first = time.perf_counter() - t0
                print(m["text"], end="", flush=True)
                continue
            if m["type"] == "transcript":
                print(f"[transcrito] {m['text']}")
                continue
            print()
            print("<", json.dumps({k: v for k, v in m.items() if k != "text"}, ensure_ascii=False))
            if m["type"] in ("answer", "error"):
                break
        print(f"cliente: primeiro token {first:.2f}s, total {time.perf_counter() - t0:.2f}s" if first
              else f"cliente: total {time.perf_counter() - t0:.2f}s")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="ws://127.0.0.1:8765/v1/ws")
    p.add_argument("--image")
    p.add_argument("--text", default="O que tem nessa cena?")
    p.add_argument("--audio")
    p.add_argument("--mode", default="auto", choices=["auto", "fast", "careful"])
    asyncio.run(main(p.parse_args()))
