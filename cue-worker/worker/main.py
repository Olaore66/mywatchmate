"""Starts the worker: python -m worker.main (settings from environment variables; see README.md)."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import signal
from concurrent.futures import ProcessPoolExecutor
from functools import partial

from .api import BackendApi
from .config import Config
from .decoder import find_qr_codes
from .health import serve_health
from .stream_eyes import stream_frames
from .supervisor import Supervisor


async def run(config: Config) -> None:
    log = logging.getLogger("worker")
    pool = ProcessPoolExecutor(max_workers=config.decoders or os.cpu_count() or 1)
    loop = asyncio.get_running_loop()

    async def decode(frame: bytes) -> list[str]:
        return await loop.run_in_executor(pool, partial(find_qr_codes, frame, config.frame_width, config.frame_height))

    api = BackendApi(config.api_base, config.token)
    supervisor = Supervisor(api, config, decode, stream_frames)
    health = await serve_health(config.health_port, supervisor.stats.as_dict)
    stop = asyncio.Event()
    for name in ("SIGINT", "SIGTERM"):
        with contextlib.suppress(NotImplementedError, AttributeError, ValueError):  # Windows has no signal handlers
            loop.add_signal_handler(getattr(signal, name), stop.set)
    log.info("watching for %s as shard %d/%d, up to %d rooms, health on :%d",
             config.api_base, config.shard, config.shards, config.max_rooms, config.health_port)
    try:
        await supervisor.run(stop)
    finally:
        health.close()
        await api.close()
        pool.shutdown(cancel_futures=True)


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = Config.from_env()
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(run(config))


if __name__ == "__main__":
    main()
