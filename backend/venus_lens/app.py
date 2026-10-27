"""Venus Lens backend: phone camera frames + question in, short spoken-style answer out.

Transport:
  WS  /v1/ws        header X-API-Key (or ?token= for clients that can't set headers)
      binary msg, 1st byte = kind: 0x01 JPEG frame, 0x02 audio utterance (asks a question)
      text msg JSON: {"type":"ask","text":"...","mode":"auto|fast|careful","id":"..."}
                     {"type":"ping"}
      server JSON: ready, transcript, delta, answer, error, pong
  POST /v1/ask      multipart: image (opt), audio (opt) or question (opt), mode
  GET  /health      open
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from typing import Awaitable, Callable

import httpx
from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile, WebSocket, \
    WebSocketDisconnect

from .config import CAREFUL_HINTS, Settings
from .deepinfra import DeepInfra, DeepInfraError
from .session import BadImage, Session, sniff_audio_name

log = logging.getLogger("venus_lens")
Emit = Callable[[dict], Awaitable[None]]

KIND_FRAME = 0x01
KIND_AUDIO = 0x02


def pick_model(s: Settings, question: str, mode: str) -> str:
    if mode == "careful":
        return s.model_careful
    if mode == "fast":
        return s.model_fast
    q = question.lower()
    return s.model_careful if any(h in q for h in CAREFUL_HINTS) else s.model_fast


def append_log(s: Settings, record: dict) -> None:
    try:
        s.data_dir.mkdir(parents=True, exist_ok=True)
        with open(s.data_dir / "interactions.jsonl", "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError as e:
        log.warning("log write failed: %s", e)


async def run_ask(app: FastAPI, session: Session, emit: Emit, *, req_id: str,
                  question: str | None = None, audio: bytes | None = None,
                  mode: str = "auto") -> dict:
    s: Settings = app.state.settings
    di: DeepInfra = app.state.di
    t0 = time.perf_counter()
    lat: dict[str, int] = {}

    if audio is not None:
        question = await di.transcribe(audio, sniff_audio_name(audio), s.model_stt, s.stt_language)
        lat["stt_ms"] = int((time.perf_counter() - t0) * 1000)
        await emit({"type": "transcript", "id": req_id, "text": question})
    question = (question or "").strip()
    if not question:
        raise ValueError("pergunta vazia (nada transcrito)")

    frame = session.latest_frame(s.frame_max_age_s)
    frame_age = round(time.time() - frame.ts, 1) if frame else None
    model = pick_model(s, question, mode)
    messages = session.build_messages(s.system_prompt, question, frame)

    t1 = time.perf_counter()
    parts: list[str] = []
    usage = None
    async for kind, val in di.chat_stream(model, messages, s.max_tokens):
        if kind == "delta":
            if not parts:
                lat["first_token_ms"] = int((time.perf_counter() - t1) * 1000)
            parts.append(val)  # type: ignore[arg-type]
            await emit({"type": "delta", "id": req_id, "text": val})
        elif kind == "usage":
            usage = val
    answer = "".join(parts).strip()
    lat["llm_ms"] = int((time.perf_counter() - t1) * 1000)
    lat["total_ms"] = int((time.perf_counter() - t0) * 1000)

    session.remember(question, answer)
    cost = await di.cost_usd(model, usage)  # type: ignore[arg-type]
    result = {"type": "answer", "id": req_id, "text": answer, "question": question,
              "model": model, "had_frame": frame is not None,
              "frame_age_s": frame_age,
              "latency_ms": lat, "usage": usage, "cost_usd": cost}
    append_log(s, {"ts": time.time(), **{k: v for k, v in result.items() if k != "type"}})
    await emit(result)
    return result


def check_token(s: Settings, sent: str | None) -> bool:
    return bool(s.api_token) and bool(sent) and secrets.compare_digest(sent, s.api_token)


async def require_key(request: Request) -> None:
    if not check_token(request.app.state.settings, request.headers.get("x-api-key")):
        raise HTTPException(401, "chave invalida ou ausente")


def create_app(settings: Settings | None = None,
               transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    s = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if not s.deepinfra_key:
            log.warning("DEEPINFRA_API_KEY ausente")
        if not s.api_token:
            log.warning("token do app ausente: todas as rotas protegidas vao responder 401")
        app.state.di = DeepInfra(s.deepinfra_key, s.base_url, transport=transport)
        yield
        await app.state.di.aclose()

    app = FastAPI(title="Venus Lens", version="0.1.0", lifespan=lifespan)
    app.state.settings = s

    @app.get("/health")
    async def health():
        return {"ok": True, "model_fast": s.model_fast, "model_careful": s.model_careful,
                "stt": s.model_stt, "auth_configured": bool(s.api_token),
                "deepinfra_configured": bool(s.deepinfra_key)}

    @app.post("/v1/ask", dependencies=[Depends(require_key)])
    async def ask(image: UploadFile | None = File(None), audio: UploadFile | None = File(None),
                  question: str | None = Form(None), mode: str = Form("auto")):
        if mode not in ("auto", "fast", "careful"):
            raise HTTPException(422, "mode deve ser auto, fast ou careful")
        session = Session(1, 0, s.max_image_side)
        if image is not None:
            data = await image.read()
            if len(data) > s.max_frame_bytes:
                raise HTTPException(413, "imagem grande demais")
            try:
                session.add_frame(data)
            except BadImage as e:
                raise HTTPException(422, str(e))
        audio_bytes = None
        if audio is not None:
            audio_bytes = await audio.read()
            if len(audio_bytes) > s.max_audio_bytes:
                raise HTTPException(413, "audio grande demais")
        if not audio_bytes and not (question or "").strip():
            raise HTTPException(422, "mande question ou audio")

        async def noop(_: dict) -> None:
            return None
        try:
            return await run_ask(app, session, noop, req_id=uuid.uuid4().hex[:8],
                                 question=question, audio=audio_bytes, mode=mode)
        except DeepInfraError as e:
            raise HTTPException(502, str(e))
        except ValueError as e:
            raise HTTPException(422, str(e))

    @app.websocket("/v1/ws")
    async def ws_endpoint(ws: WebSocket):
        sent = ws.headers.get("x-api-key") or ws.query_params.get("token")
        if not check_token(s, sent):
            await ws.close(code=4401, reason="unauthorized")
            return
        await ws.accept()
        session = Session(s.frame_buffer, s.history_turns, s.max_image_side)
        send_lock = asyncio.Lock()
        current: asyncio.Task | None = None

        async def emit(msg: dict) -> None:
            async with send_lock:
                await ws.send_text(json.dumps(msg, ensure_ascii=False))

        def start(**kw) -> asyncio.Task:
            nonlocal current
            if current and not current.done():
                current.cancel()  # a new question supersedes the one in flight
            req_id = kw.pop("req_id", None) or uuid.uuid4().hex[:8]

            async def job():
                try:
                    await run_ask(app, session, emit, req_id=req_id, **kw)
                except asyncio.CancelledError:
                    raise
                except (DeepInfraError, ValueError) as e:
                    await emit({"type": "error", "id": req_id, "message": str(e)})
                except Exception as e:  # keep the socket alive on unexpected failures
                    log.exception("ask failed")
                    await emit({"type": "error", "id": req_id, "message": f"falha interna: {e}"})
            current = asyncio.create_task(job())
            return current

        await emit({"type": "ready", "model_fast": s.model_fast, "model_careful": s.model_careful})
        try:
            while True:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                if msg.get("bytes") is not None:
                    data: bytes = msg["bytes"]
                    if not data:
                        continue
                    kind, payload = data[0], data[1:]
                    if kind == KIND_FRAME:
                        if len(payload) > s.max_frame_bytes:
                            await emit({"type": "error", "message": "frame grande demais"})
                            continue
                        try:
                            session.add_frame(payload)
                        except BadImage as e:
                            await emit({"type": "error", "message": str(e)})
                    elif kind == KIND_AUDIO:
                        if len(payload) > s.max_audio_bytes:
                            await emit({"type": "error", "message": "audio grande demais"})
                            continue
                        start(audio=payload)
                    else:
                        await emit({"type": "error", "message": f"tipo binario desconhecido {kind}"})
                elif msg.get("text") is not None:
                    try:
                        obj = json.loads(msg["text"])
                    except json.JSONDecodeError:
                        await emit({"type": "error", "message": "json invalido"})
                        continue
                    t = obj.get("type")
                    if t == "ping":
                        await emit({"type": "pong", "frames": session.frames_received})
                    elif t == "ask":
                        mode = obj.get("mode", "auto")
                        if mode not in ("auto", "fast", "careful"):
                            mode = "auto"
                        start(question=str(obj.get("text", "")), mode=mode, req_id=obj.get("id"))
                    else:
                        await emit({"type": "error", "message": f"tipo desconhecido {t}"})
        except WebSocketDisconnect:
            pass
        finally:
            if current and not current.done():
                current.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await current

    return app
