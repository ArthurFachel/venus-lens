"""Runtime config. Everything comes from env vars, optionally loaded from a .env file.

Lookup order for each key: real environment, then the .env file. The .env path is
$VENUS_LENS_ENV_FILE if set, else <repo root>/.env (see .env.example). Secrets never live in code.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

HOME = Path.home()
REPO_ROOT = Path(__file__).resolve().parents[2]


def load_dotenv(path: Path) -> dict[str, str]:
    """Parse KEY=value lines (# comments, optional quotes, optional `export `). Missing file -> {}."""
    out: dict[str, str] = {}
    try:
        text = path.read_text()
    except OSError:
        return out
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        key, val = line.split("=", 1)
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
            val = val[1:-1]
        out[key.strip()] = val
    return out


def env_file() -> Path:
    return Path(os.environ.get("VENUS_LENS_ENV_FILE", REPO_ROOT / ".env"))


SYSTEM_PROMPT = (
    "Você é a Venus, assistente que enxerga pela câmera do celular do usuário. "
    "Responda em português do Brasil, curto e natural, porque a resposta vai ser lida em voz alta: "
    "sem markdown, sem listas, sem emojis, no máximo 3 frases. "
    "Se não der pra ler ou identificar algo com clareza, diga isso em vez de chutar. "
    "Se não houver imagem, responda só com base na pergunta e avise que não está vendo nada."
)

CAREFUL_HINTS = ("lê ", "leia", "ler ", "o que está escrito", "o que ta escrito", "o que tá escrito",
                 "rótulo", "rotulo", "ingrediente", "documento", "traduz", "letra", "número", "numero")


@dataclass
class Settings:
    deepinfra_key: str = ""
    api_token: str = ""
    base_url: str = "https://api.deepinfra.com/v1/openai"
    # Picked by bake-off on 2026-10-27 (n=2 images): Qwen3.5-9B best cheap, gemini-flash-lite best at text.
    model_fast: str = "Qwen/Qwen3.5-9B"
    model_careful: str = "google/gemini-3.1-flash-lite"
    model_stt: str = "openai/whisper-large-v3-turbo"
    stt_language: str = "pt"
    max_tokens: int = 300
    # Phone picks 480p/720p/1080p; anything above 1280 is shrunk here to cap vision-token cost.
    max_image_side: int = 1280
    frame_max_age_s: float = 15.0
    frame_buffer: int = 5
    history_turns: int = 4
    max_frame_bytes: int = 2_000_000
    max_audio_bytes: int = 5_000_000
    data_dir: Path = field(default_factory=lambda: HOME / ".local/share/venus-lens")
    log_images: bool = False
    system_prompt: str = SYSTEM_PROMPT
    host: str = "0.0.0.0"
    port: int = 8765

    @classmethod
    def from_env(cls, path: Path | None = None) -> "Settings":
        file_vals = load_dotenv(path or env_file())

        def get(key: str, default: str = "") -> str:
            return os.environ.get(key) or file_vals.get(key) or default

        s = cls()
        s.deepinfra_key = get("DEEPINFRA_API_KEY")
        s.api_token = get("VENUS_LENS_TOKEN")
        s.model_fast = get("VENUS_LENS_MODEL_FAST", s.model_fast)
        s.model_careful = get("VENUS_LENS_MODEL_CAREFUL", s.model_careful)
        s.model_stt = get("VENUS_LENS_MODEL_STT", s.model_stt)
        s.max_image_side = int(get("VENUS_LENS_MAX_IMAGE_SIDE", str(s.max_image_side)))
        if get("VENUS_LENS_DATA_DIR"):
            s.data_dir = Path(get("VENUS_LENS_DATA_DIR")).expanduser()
        s.log_images = get("VENUS_LENS_LOG_IMAGES", "0") == "1"
        s.host = get("VENUS_LENS_HOST", s.host)
        s.port = int(get("VENUS_LENS_PORT", str(s.port)))
        return s
