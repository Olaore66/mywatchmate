"""Settings, from environment variables (so the same image runs on a laptop, staging and production)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    api_base: str
    token: str
    max_rooms: int = 40          # rooms this worker watches at once; more go to another shard
    shard: int = 0               # this worker takes the rooms where (session id) mod shards == shard
    shards: int = 1
    poll_seconds: float = 15.0   # how often to ask which sessions are live
    fps: float = 1.0             # frames looked at per second, per room
    frame_width: int = 960
    frame_height: int = 540
    dedupe_minutes: float = 10.0  # the same code in the same session is reported once per this long
    health_port: int = 8090
    decoders: int = 0            # decoding processes; 0 = one per CPU
    watch_calls: bool = True     # read screen shares in YouTube/Vimeo rooms' calls

    @staticmethod
    def from_env(env: dict[str, str] | None = None) -> "Config":
        env = dict(os.environ if env is None else env)
        token = env.get("INTERNAL_CUE_TOKEN", "").strip()
        if not token:
            raise SystemExit("INTERNAL_CUE_TOKEN is not set: the backend would refuse every request.")
        shard, shards = _shard(env.get("WORKER_SHARD", "0/1"))
        return Config(
            api_base=env.get("MWM_API_BASE", "https://51-24-72-13.sslip.io").rstrip("/"),
            token=token,
            max_rooms=int(env.get("MAX_ROOMS", "40")),
            shard=shard,
            shards=shards,
            poll_seconds=float(env.get("POLL_SECONDS", "15")),
            fps=float(env.get("FRAMES_PER_SECOND", "1")),
            dedupe_minutes=float(env.get("DEDUPE_MINUTES", "10")),
            health_port=int(env.get("HEALTH_PORT", "8090")),
            decoders=int(env.get("DECODERS", "0")),
            watch_calls=env.get("WATCH_CALLS", "true").strip().lower() not in {"false", "0", "no"},
        )


def _shard(value: str) -> tuple[int, int]:
    """'1/3' means shard 1 of 3 (0, 1 and 2)."""
    try:
        shard, shards = (int(part) for part in value.split("/", 1))
    except ValueError as e:
        raise SystemExit(f"WORKER_SHARD must look like 0/1, not {value!r}") from e
    if shards < 1 or not 0 <= shard < shards:
        raise SystemExit(f"WORKER_SHARD {value!r} is out of range")
    return shard, shards
