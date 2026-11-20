"""The control room: every few seconds, find the live rooms that are ours and keep exactly one watcher on each.

Each watcher reads frames from its room's stream and has them decoded. The latest frame always wins: while one
frame is being decoded, newer ones replace each other, so a slow moment never builds up a backlog. A watcher that
fails is restarted with a growing wait; a room that ends has its watcher stopped.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field

from .api import BackendApi, LiveSession
from .config import Config
from .dedupe import Dedupe
from .liveness import Liveness

log = logging.getLogger(__name__)

FrameSource = Callable[[str, int, int, float], AsyncIterator[bytes]]
Decode = Callable[[bytes], Awaitable[list[str]]]

# Direct links (URL) are watched when they turn out to be live streams. Pre-recorded files need the room's
# play/pause/seek, and YouTube and Vimeo need the screen-share route; both come in later steps.
DIRECT = {"URL", "LIVESTREAM"}
MAX_BACKOFF_SECONDS = 300.0
HEALTHY_RUN_SECONDS = 60.0


@dataclass
class Stats:
    rooms: int = 0
    frames: int = 0
    frames_skipped: int = 0
    codes_found: int = 0
    cues_sent: int = 0
    cues_shown: int = 0
    watcher_failures: int = 0
    last_poll_ok: bool = False
    skipped_sources: dict[str, int] = field(default_factory=dict)

    def as_dict(self) -> dict:
        return dict(self.__dict__)


class Supervisor:
    def __init__(self, api: BackendApi, config: Config, decode: Decode, frames: FrameSource,
                 clock: Callable[[], float] = time.monotonic, liveness: Liveness | None = None) -> None:
        self._api = api
        self._liveness = liveness or Liveness(clock=clock)
        self._config = config
        self._decode = decode
        self._frames = frames
        self._clock = clock
        self._dedupe = Dedupe(config.dedupe_minutes * 60, clock)
        self._watchers: dict[str, asyncio.Task] = {}
        self._failures: dict[str, int] = {}
        self._retry_after: dict[str, float] = {}
        self.stats = Stats()

    def mine(self, session_id: str) -> bool:
        """Shards split rooms between workers by session id, so two workers never watch the same room."""
        return uuid.UUID(session_id).int % self._config.shards == self._config.shard

    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await self.reconcile_once()
            try:
                await asyncio.wait_for(stop.wait(), timeout=self._config.poll_seconds)
            except TimeoutError:
                pass
        await self.stop_all()

    async def reconcile_once(self) -> None:
        try:
            live = await self._api.live_sessions()
            self.stats.last_poll_ok = True
        except Exception as e:  # noqa: BLE001 - the backend being away for a moment must not stop the worker
            self.stats.last_poll_ok = False
            log.warning("couldn't ask which sessions are live: %s", e)
            return
        wanted = {s.session_id: s for s in live if self.mine(s.session_id)}
        skipped: dict[str, int] = {}
        for session_id in list(self._watchers):                         # rooms that ended (or stopped being ours)
            if session_id not in wanted:
                self._watchers.pop(session_id).cancel()
                self._dedupe.forget_session(session_id)
                self._failures.pop(session_id, None)
                self._retry_after.pop(session_id, None)
        for session_id, task in list(self._watchers.items()):          # watchers that stopped by themselves
            if task.done():
                del self._watchers[session_id]
        for session_id, session in wanted.items():                      # new rooms, up to the cap
            if session_id in self._watchers:
                continue
            reason = await self._why_not_watchable(session)
            if reason is not None:
                skipped[reason] = skipped.get(reason, 0) + 1
                continue
            if len(self._watchers) >= self._config.max_rooms:
                log.warning("at the cap of %d rooms; add a shard", self._config.max_rooms)
                break
            if self._clock() < self._retry_after.get(session_id, 0.0):
                continue
            self._watchers[session_id] = asyncio.create_task(self._watch(session), name=f"watch-{session_id}")
        self.stats.rooms = len(self._watchers)
        self.stats.skipped_sources = skipped

    async def _why_not_watchable(self, session: LiveSession) -> str | None:
        """None when we can watch it; otherwise a short reason (shown on /health)."""
        if session.source is None:
            return "NOTHING_PLAYING"
        if session.source not in DIRECT or not session.stream_url:
            return session.source
        if not await self._liveness.is_live(session.stream_url):
            return "RECORDING"
        return None

    async def stop_all(self) -> None:
        for task in self._watchers.values():
            task.cancel()
        await asyncio.gather(*self._watchers.values(), return_exceptions=True)
        self._watchers.clear()

    async def _watch(self, session: LiveSession) -> None:
        started = self._clock()
        try:
            await self._read_and_decode(session)
            failed = False
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 - one room's trouble must never affect the others
            log.warning("watching session %s failed: %s", session.session_id, e)
            failed = True
        ran = self._clock() - started
        if failed or ran < HEALTHY_RUN_SECONDS:
            self.stats.watcher_failures += 1
            failures = self._failures.get(session.session_id, 0) + 1
            self._failures[session.session_id] = failures
            self._retry_after[session.session_id] = self._clock() + min(MAX_BACKOFF_SECONDS, 5.0 * 2 ** (failures - 1))
        else:
            self._failures.pop(session.session_id, None)

    async def _read_and_decode(self, session: LiveSession) -> None:
        cfg = self._config
        latest: bytes | None = None
        finished = False
        ready = asyncio.Event()

        async def read() -> None:
            nonlocal latest, finished
            try:
                async for frame in self._frames(session.stream_url, cfg.frame_width, cfg.frame_height, cfg.fps):
                    if latest is not None:
                        self.stats.frames_skipped += 1
                    latest = frame
                    self.stats.frames += 1
                    ready.set()
            finally:
                finished = True
                ready.set()

        reader = asyncio.create_task(read())
        try:
            while True:
                await ready.wait()
                ready.clear()
                if latest is not None:
                    frame, latest = latest, None
                    for code in await self._decode(frame):
                        self.stats.codes_found += 1
                        if self._dedupe.is_new(session.session_id, code):
                            await self._report(session.session_id, code)
                if finished and latest is None:
                    break
        finally:
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)

    async def _report(self, session_id: str, code: str) -> None:
        outcome = await self._api.report_cue(session_id, code)
        self.stats.cues_sent += 1
        if outcome and outcome.get("status") == "SHOWN":
            self.stats.cues_shown += 1
        log.info("session %s: QR reported, backend said %s", session_id, outcome)
