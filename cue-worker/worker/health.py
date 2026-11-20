"""A tiny /health page: is the worker alive, and what is it doing (rooms, frames, cues)."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable


async def serve_health(port: int, snapshot: Callable[[], dict]) -> asyncio.Server:
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
            body = json.dumps({"status": "UP", **snapshot()}).encode()
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                         + f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
            await writer.drain()
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, TimeoutError, ConnectionError):
            pass
        finally:
            writer.close()

    return await asyncio.start_server(handle, "0.0.0.0", port)
