"""Per-connection state: recent camera frames and a short Q/A history."""
from __future__ import annotations

import base64
import io
import time
from collections import deque
from dataclasses import dataclass

from PIL import Image, UnidentifiedImageError


class BadImage(ValueError):
    pass


@dataclass
class Frame:
    jpeg: bytes
    ts: float


def normalize_jpeg(data: bytes, max_side: int) -> bytes:
    """Validate the image and shrink it if the client sent something bigger than needed."""
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except (UnidentifiedImageError, OSError) as e:
        raise BadImage(f"imagem invalida: {e}") from e
    if im.format == "JPEG" and max(im.size) <= max_side:
        return data
    im = im.convert("RGB")
    im.thumbnail((max_side, max_side))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return out.getvalue()


def sniff_audio_name(data: bytes) -> str:
    if data[:4] == b"RIFF":
        return "audio.wav"
    if data[4:8] == b"ftyp":
        return "audio.m4a"
    if data[:4] == b"OggS":
        return "audio.ogg"
    if data[:4] == b"\x1aE\xdf\xa3":
        return "audio.webm"
    if data[:3] == b"ID3" or data[:2] in (b"\xff\xfb", b"\xff\xf3"):
        return "audio.mp3"
    return "audio.bin"


class Session:
    def __init__(self, frame_buffer: int, history_turns: int, max_side: int):
        self.frames: deque[Frame] = deque(maxlen=frame_buffer)
        self.history: deque[tuple[str, str]] = deque(maxlen=history_turns)
        self.max_side = max_side
        self.frames_received = 0

    def add_frame(self, data: bytes, ts: float | None = None) -> Frame:
        f = Frame(normalize_jpeg(data, self.max_side), ts or time.time())
        self.frames.append(f)
        self.frames_received += 1
        return f

    def latest_frame(self, max_age_s: float) -> Frame | None:
        if not self.frames:
            return None
        f = self.frames[-1]
        return f if time.time() - f.ts <= max_age_s else None

    def build_messages(self, system: str, question: str, frame: Frame | None) -> list[dict]:
        msgs: list[dict] = [{"role": "system", "content": system}]
        for q, a in self.history:
            msgs.append({"role": "user", "content": q})
            msgs.append({"role": "assistant", "content": a})
        content: list[dict] = []
        if frame is not None:
            url = "data:image/jpeg;base64," + base64.b64encode(frame.jpeg).decode()
            content.append({"type": "image_url", "image_url": {"url": url}})
        else:
            question = "[sem imagem da câmera agora] " + question
        content.append({"type": "text", "text": question})
        msgs.append({"role": "user", "content": content})
        return msgs

    def remember(self, question: str, answer: str) -> None:
        self.history.append((question, answer))
