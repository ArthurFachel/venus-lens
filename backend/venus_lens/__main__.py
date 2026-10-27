"""Run: python -m venus_lens [--host H] [--port P]  (defaults from VENUS_LENS_HOST / VENUS_LENS_PORT)"""
import argparse
import logging

import uvicorn

from .app import create_app
from .config import Settings


def main() -> None:
    s = Settings.from_env()
    p = argparse.ArgumentParser(prog="venus_lens")
    p.add_argument("--host", default=s.host)
    p.add_argument("--port", type=int, default=s.port)
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(create_app(s), host=a.host, port=a.port, ws_max_size=8 * 1024 * 1024)


if __name__ == "__main__":
    main()
