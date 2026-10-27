# Venus Lens

Phone camera + voice in, short spoken answer out. "Meta glasses" on the phone.

An Android app streams camera frames (or a single frozen photo) and voice questions over a
WebSocket to a small FastAPI backend. The backend answers with a vision model on
[DeepInfra](https://deepinfra.com), streams the text back, and the phone reads it aloud with
Android TextToSpeech. UI and spoken answers are in Brazilian Portuguese.

## Layout

    backend/    FastAPI service (WebSocket + REST)
    android/    Kotlin app: streaming / single-photo modes, Quick Settings tile, settings and debug screens
    scripts/install_android_toolchain.sh   JDK 17 + Android SDK + Gradle, user-local, no sudo

## Configuration

Everything is configured through environment variables, usually from a `.env` file at the repo
root (gitignored). Start from the example:

    cp .env.example .env

| Variable | Used by | Meaning |
|---|---|---|
| `DEEPINFRA_API_KEY` | backend | DeepInfra API key |
| `VENUS_LENS_TOKEN` | backend | Shared secret the phone sends as `X-API-Key`. Empty = every protected route answers 401 |
| `VENUS_LENS_HOST`, `VENUS_LENS_PORT` | backend | Bind address (default `0.0.0.0:8765`) |
| `VENUS_LENS_MODEL_FAST` / `_CAREFUL` / `_STT` | backend | Model overrides |
| `VENUS_LENS_MAX_IMAGE_SIDE` | backend | Longest image side sent to the model (default 1280) |
| `VENUS_LENS_DATA_DIR` | backend | Where the interaction log goes (default `~/.local/share/venus-lens`) |
| `VENUS_LENS_URL` | Android build | Default server URL baked into the APK, e.g. `ws://192.168.0.10:8765/v1/ws` |

Real environment variables win over the file. `VENUS_LENS_ENV_FILE` points the backend at a
different file.

The app token is not baked into the APK: type it in the app under Ajustes > Chave de API.

## Backend

    cd backend
    uv sync
    uv run pytest                    # offline, DeepInfra mocked
    uv run python -m venus_lens      # host/port from .env, or --host / --port

Default models:

    fast     Qwen/Qwen3.5-9B
    careful  google/gemini-3.1-flash-lite   (mode=careful, or the question mentions reading/label/translate)
    stt      openai/whisper-large-v3-turbo

## Android

    scripts/install_android_toolchain.sh          # once, if you have no Android SDK
    cd android
    JAVA_HOME=~/.local/opt/jdk-17 ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

minSdk 34. The phone must reach the backend: same network, or a VPN such as Tailscale.

## Protocol

    WS /v1/ws   header X-API-Key (or ?token=)
      client binary: 0x01 + JPEG  -> camera frame (kept 15 s, last 5 buffered)
                     0x02 + audio -> question as speech (wav/m4a/ogg/webm/mp3)
      client text:   {"type":"ask","text":"...","mode":"auto|fast|careful","id":"q1"}
                     {"type":"ping"}
      server:        ready | transcript | delta (stream, speak per sentence) | answer | error | pong
      A new question cancels the one in flight. Last 4 Q/A turns are kept per connection.
    POST /v1/ask  multipart image?, audio? | question, mode
    GET  /health  open

Simulate the phone:

    uv run python tools/ws_client.py --image foto.jpg --text "o que é isso?"
    uv run python tools/ws_client.py --image foto.jpg --audio pergunta.wav

## Logs

`$VENUS_LENS_DATA_DIR/interactions.jsonl`: question, answer, model, latency, cost. Images are never stored.
