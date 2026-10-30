"""Offline tests: DeepInfra is faked with httpx.MockTransport."""
import io
import json
import time

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from venus_lens.app import KIND_AUDIO, KIND_FRAME, create_app, pick_model
from venus_lens.config import Settings
from venus_lens.session import Session, sniff_audio_name

TOKEN = "test-token-123"


def jpeg(w=1600, h=900) -> bytes:
    b = io.BytesIO()
    Image.new("RGB", (w, h), (200, 180, 40)).save(b, "JPEG")
    return b.getvalue()


class FakeDI:
    def __init__(self, answer="São bananas maduras.", fail=False):
        self.answer, self.fail = answer, fail
        self.chat_bodies: list[dict] = []
        self.stt_calls = 0

    def __call__(self, req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/models/list"):
            return httpx.Response(200, json=[{"model_name": "Qwen/Qwen3.5-9B", "pricing": {
                "cents_per_input_token": 1e-5, "cents_per_output_token": 1.5e-5}}])
        if req.url.path.endswith("/audio/transcriptions"):
            self.stt_calls += 1
            return httpx.Response(200, json={"text": "o que é isso?"})
        if req.url.path.endswith("/chat/completions"):
            if self.fail:
                return httpx.Response(500, text="boom")
            self.chat_bodies.append(json.loads(req.content))
            words = self.answer.split(" ")
            lines = [f'data: {json.dumps({"choices": [{"delta": {"content": w + " "}}]})}' for w in words]
            lines.append('data: ' + json.dumps({"choices": [], "usage": {"prompt_tokens": 300, "completion_tokens": 10}}))
            lines.append("data: [DONE]")
            return httpx.Response(200, text="\n\n".join(lines) + "\n\n",
                                  headers={"content-type": "text/event-stream"})
        return httpx.Response(404)


def make(tmp_path, fake=None, token=TOKEN):
    fake = fake or FakeDI()
    s = Settings(deepinfra_key="k", api_token=token, data_dir=tmp_path, max_image_side=768)
    app = create_app(s, transport=httpx.MockTransport(fake))
    return TestClient(app), fake


def test_health_open(tmp_path):
    c, _ = make(tmp_path)
    with c:
        r = c.get("/health")
    assert r.status_code == 200 and r.json()["auth_configured"] is True


def test_rest_requires_key(tmp_path):
    c, _ = make(tmp_path)
    with c:
        assert c.post("/v1/ask", data={"question": "oi"}).status_code == 401
        assert c.post("/v1/ask", data={"question": "oi"}, headers={"X-API-Key": "nope"}).status_code == 401


def test_empty_server_token_rejects_everything(tmp_path):
    c, _ = make(tmp_path, token="")
    with c:
        assert c.post("/v1/ask", data={"question": "oi"}, headers={"X-API-Key": ""}).status_code == 401


def test_rest_ask_with_image_downscales_and_logs(tmp_path):
    c, fake = make(tmp_path)
    with c:
        r = c.post("/v1/ask", headers={"X-API-Key": TOKEN},
                   files={"image": ("f.jpg", jpeg(), "image/jpeg")}, data={"question": "o que é isso?"})
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["text"] == "São bananas maduras." and j["had_frame"] is True
    assert j["cost_usd"] == pytest.approx((300 * 1e-5 + 10 * 1.5e-5) / 100)
    body = fake.chat_bodies[0]
    assert body["model"] == "Qwen/Qwen3.5-9B" and body["reasoning_effort"] == "none"
    url = body["messages"][-1]["content"][0]["image_url"]["url"]
    import base64
    im = Image.open(io.BytesIO(base64.b64decode(url.split(",", 1)[1])))
    assert max(im.size) == 768
    rec = json.loads((tmp_path / "interactions.jsonl").read_text().splitlines()[0])
    assert rec["question"] == "o que é isso?" and "image" not in json.dumps(rec).lower().replace("had_frame", "")


def test_rest_rejects_garbage_image_and_empty(tmp_path):
    c, _ = make(tmp_path)
    with c:
        h = {"X-API-Key": TOKEN}
        assert c.post("/v1/ask", headers=h, files={"image": ("f.jpg", b"notimg", "image/jpeg")},
                      data={"question": "x"}).status_code == 422
        assert c.post("/v1/ask", headers=h, data={"question": "  "}).status_code == 422


def test_upstream_error_is_502(tmp_path):
    c, _ = make(tmp_path, FakeDI(fail=True))
    with c:
        r = c.post("/v1/ask", headers={"X-API-Key": TOKEN}, data={"question": "oi"})
    assert r.status_code == 502


def test_ws_rejects_bad_token(tmp_path):
    c, _ = make(tmp_path)
    with c:
        with pytest.raises(Exception):
            with c.websocket_connect("/v1/ws", headers={"X-API-Key": "nope"}) as ws:
                ws.receive_text()


def recv_until(ws, kind):
    out = []
    while True:
        m = json.loads(ws.receive_text())
        out.append(m)
        if m["type"] in (kind, "error"):
            return out


def test_ws_frame_then_text_ask_streams(tmp_path):
    c, fake = make(tmp_path)
    with c:
        with c.websocket_connect("/v1/ws", headers={"X-API-Key": TOKEN}) as ws:
            assert json.loads(ws.receive_text())["type"] == "ready"
            ws.send_bytes(bytes([KIND_FRAME]) + jpeg())
            ws.send_text(json.dumps({"type": "ping"}))
            assert json.loads(ws.receive_text())["frames"] == 1
            ws.send_text(json.dumps({"type": "ask", "text": "o que é isso?", "id": "q1"}))
            msgs = recv_until(ws, "answer")
    deltas = [m for m in msgs if m["type"] == "delta"]
    final = msgs[-1]
    assert len(deltas) >= 2 and final["type"] == "answer" and final["id"] == "q1"
    assert final["had_frame"] is True and "first_token_ms" in final["latency_ms"]


def test_ws_audio_ask_and_history(tmp_path):
    c, fake = make(tmp_path)
    with c:
        with c.websocket_connect("/v1/ws?token=" + TOKEN) as ws:
            ws.receive_text()
            ws.send_bytes(bytes([KIND_AUDIO]) + b"RIFF....WAVEfmt fake")
            msgs = recv_until(ws, "answer")
            assert msgs[0] == {"type": "transcript", "id": msgs[0]["id"], "text": "o que é isso?"}
            assert msgs[-1]["had_frame"] is False
            ws.send_text(json.dumps({"type": "ask", "text": "e agora?"}))
            recv_until(ws, "answer")
    assert fake.stt_calls == 1
    second = fake.chat_bodies[1]["messages"]
    assert [m["role"] for m in second] == ["system", "user", "assistant", "user"]


def test_ws_bad_frame_keeps_socket(tmp_path):
    c, _ = make(tmp_path)
    with c:
        with c.websocket_connect("/v1/ws", headers={"X-API-Key": TOKEN}) as ws:
            ws.receive_text()
            ws.send_bytes(bytes([KIND_FRAME]) + b"garbage")
            assert json.loads(ws.receive_text())["type"] == "error"
            ws.send_text(json.dumps({"type": "ping"}))
            assert json.loads(ws.receive_text())["type"] == "pong"


def test_stale_frame_ignored():
    s = Session(3, 2, 768)
    s.add_frame(jpeg(100, 100), ts=time.time() - 60)
    assert s.latest_frame(15) is None
    s.add_frame(jpeg(100, 100))
    assert s.latest_frame(15) is not None


def test_journal_plain_question(tmp_path):
    c, fake = make(tmp_path)
    with c:
        r = c.post("/v1/ask", headers={"X-API-Key": TOKEN}, data={"question": "que horas são em Tóquio?"})
    assert r.status_code == 200 and r.json()["saved"] is False
    days = list((tmp_path / "diario").glob("*.md"))
    assert len(days) == 1
    text = days[0].read_text()
    assert text.startswith("# Venus Lens, ") and "que horas são em Tóquio?" in text
    assert "São bananas maduras." in text and "sem câmera" in text and "### Nota" not in text
    assert not (tmp_path / "diario" / "img").exists() and len(fake.chat_bodies) == 1


def test_journal_save_request_keeps_frame_and_note(tmp_path):
    c, fake = make(tmp_path)
    with c:
        with c.websocket_connect("/v1/ws", headers={"X-API-Key": TOKEN}) as ws:
            ws.receive_text()
            ws.send_bytes(bytes([KIND_FRAME]) + jpeg())
            ws.send_text(json.dumps({"type": "ask", "text": "guarda esse quadro pra mim", "id": "q9"}))
            final = recv_until(ws, "answer")[-1]
    assert final["saved"] is True
    assert len(fake.chat_bodies) == 2  # spoken answer + the note
    note_call = fake.chat_bodies[1]
    assert note_call["model"] == Settings().model_careful
    assert note_call["messages"][0]["content"].startswith("Você recebe uma foto")
    imgs = list((tmp_path / "diario" / "img").rglob("*.jpg"))
    assert len(imgs) == 1 and imgs[0].name.endswith("-q9.jpg")
    text = next((tmp_path / "diario").glob("*.md")).read_text()
    assert "· guardado" in text and "### Nota" in text and f"](img/{imgs[0].parent.name}/{imgs[0].name})" in text


def test_journal_save_without_frame_is_plain(tmp_path):
    c, fake = make(tmp_path)
    with c:
        r = c.post("/v1/ask", headers={"X-API-Key": TOKEN}, data={"question": "anota isso"})
    assert r.json()["saved"] is False and len(fake.chat_bodies) == 1


def test_journal_off(tmp_path):
    fake = FakeDI()
    s = Settings(deepinfra_key="k", api_token=TOKEN, data_dir=tmp_path, journal=False)
    with TestClient(create_app(s, transport=httpx.MockTransport(fake))) as c:
        c.post("/v1/ask", headers={"X-API-Key": TOKEN}, data={"question": "oi"})
    assert not (tmp_path / "diario").exists()


def test_wants_save():
    from venus_lens.journal import wants_save
    for q in ("guarda isso", "Anota o quadro", "salva essa página", "lembra disso depois", "registra aí"):
        assert wants_save(q), q
    for q in ("o que é isso?", "onde fica o guardanapo?", "o salvador é longe?", "qual o registro?"):
        assert not wants_save(q), q


def test_pick_model_and_sniff():
    st = Settings()
    assert pick_model(st, "o que é isso?", "auto") == st.model_fast
    assert pick_model(st, "lê esse rótulo pra mim", "auto") == st.model_careful
    assert pick_model(st, "lê esse rótulo", "fast") == st.model_fast
    assert sniff_audio_name(b"RIFFxxxxWAVE") == "audio.wav"
    assert sniff_audio_name(b"\x00\x00\x00\x18ftypM4A ") == "audio.m4a"


def test_dotenv_and_env_precedence(tmp_path, monkeypatch):
    from venus_lens.config import Settings, load_dotenv
    f = tmp_path / ".env"
    f.write_text('# comment\nDEEPINFRA_API_KEY="from-file"\nexport VENUS_LENS_TOKEN=tok\nVENUS_LENS_PORT=9000\n\nNOT A LINE\n')
    assert load_dotenv(f)["DEEPINFRA_API_KEY"] == "from-file"
    assert load_dotenv(tmp_path / "missing") == {}
    monkeypatch.delenv("VENUS_LENS_TOKEN", raising=False)
    monkeypatch.setenv("DEEPINFRA_API_KEY", "from-env")
    s = Settings.from_env(f)
    assert s.deepinfra_key == "from-env"  # real env wins
    assert s.api_token == "tok"
    assert s.port == 9000
