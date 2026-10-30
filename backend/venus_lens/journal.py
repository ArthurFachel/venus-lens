"""Daily journal: every answered question lands in a Markdown note of the day.

  <journal_dir>/2026-10-30.md          one file per day, one "## HH:MM" section per question
  <journal_dir>/img/2026-10-30/*.jpg   only for "guarda isso" requests

Ordinary questions get question + answer. When the question asks to keep something
("guarda isso", "anota o quadro", "salva essa página"), the frame is saved and a second,
careful model call writes a faithful note of what is in it (transcription + short topics).
That call runs in the background so the spoken answer is not delayed.
Plain Markdown with relative image links: opens fine in Obsidian or any editor.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

SAVE_RE = re.compile(
    r"\b(?:guarda|guardar|guarde|salva|salvar|salve|anota|anotar|anote|registra|registrar|registre|"
    r"memoriza|memorizar|memorize|lembra disso|lembre disso)\b")

NOTE_PROMPT = (
    "Você recebe uma foto tirada pela câmera do celular do usuário e o pedido dele. "
    "Escreva uma nota em Markdown, em português do Brasil, para o diário. "
    "Se houver texto legível (quadro, slide, documento, rótulo), transcreva fielmente, mantendo fórmulas "
    "em texto simples. Depois, se ajudar, resuma em até 5 tópicos curtos. "
    "Marque [ilegível] onde não der pra ler e não invente nada que não esteja na imagem. "
    "Sem título de primeiro nível, no máximo 30 linhas."
)


def fold(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def wants_save(question: str) -> bool:
    return bool(SAVE_RE.search(fold(question or "")))


@dataclass
class Entry:
    when: datetime
    question: str
    answer: str
    model: str
    had_frame: bool
    req_id: str
    image: Path | None = None  # absolute path of the saved frame
    note: str | None = None
    saved: bool = False


def render(entry: Entry, base: Path) -> str:
    head = entry.when.strftime("%H:%M") + (" · guardado" if entry.saved else "")
    meta = f"{entry.model} · {'com câmera' if entry.had_frame else 'sem câmera'} · {entry.req_id}"
    out = [f"## {head}", "", f"**Pergunta:** {entry.question.strip()}", "",
           f"**Venus:** {entry.answer.strip()}", "", f"<small>{meta}</small>", ""]
    if entry.image is not None:
        out += [f"![]({entry.image.relative_to(base).as_posix()})", ""]
    if entry.note is not None:
        out += ["### Nota", "", entry.note.strip(), ""]
    return "\n".join(out) + "\n"


def day_file(base: Path, when: datetime) -> Path:
    return base / f"{when:%Y-%m-%d}.md"


def save_image(base: Path, when: datetime, req_id: str, jpeg: bytes) -> Path:
    folder = base / "img" / f"{when:%Y-%m-%d}"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{when:%H%M%S}-{re.sub(r'[^A-Za-z0-9_-]', '', req_id)[:16] or 'x'}.jpg"
    path.write_bytes(jpeg)
    return path


def append(base: Path, entry: Entry) -> Path:
    """Append one section to the day's note (creating it with a title). Returns the file."""
    base.mkdir(parents=True, exist_ok=True)
    path = day_file(base, entry.when)
    new = not path.exists()
    with open(path, "a", encoding="utf-8") as fh:
        if new:
            fh.write(f"# Venus Lens, {entry.when:%d/%m/%Y}\n\n")
        fh.write(render(entry, base))
    return path
