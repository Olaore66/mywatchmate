"""The control room: every few seconds, find the live rooms that are ours and keep exactly one watcher on each.

Each room gets a watch plan:
  LIVE       a live stream: read it as it comes
  RECORDING  a recording or file: read it from where the room is, at real-time pace; read again from the new spot when
             someone seeks; stop while the room is paused
  CALL       a YouTube/Vimeo room with a screen share in its call: watch the shared screen as a hidden viewer
A watcher whose plan changes (a seek, a pause) is replaced at once; one that fails is restarted with a growing wait;
a room that ends has its watcher stopped. The latest frame always wins: while one frame is being decoded, newer ones
replace each other, so a slow moment never builds up a backlog. Rooms watching the same live stream share a single
reader and decode (see shared_streams.py); each still dedupes and reports its own cue.
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
from .shared_streams import SharedStreams

log = logging.getLogger(__name__)

FrameSource = Callable[..., AsyncIterator[bytes]]                      # (url, width, height, fps, start_seconds, realtime)
CallSource = Callable[[LiveSession, int, int, float], AsyncIterator[bytes]]   # (session, width, height, fps)
Decode = Callable[[bytes], Awaitable[list[str]]]

DIRECT = {"URL", "LIVESTREAM"}
MAX_BACKOFF_SECONDS = 300.0
HEALTHY_RUN_SECONDS = 60.0


@dataclass(frozen=True)
class Plan:
    kind: str                         # LIVE, RECORDING or CALL
    key: tuple                        # a different key means "start again"
    start_seconds: float | None = None


@dataclass
class Stats:
    rooms: int = 0
    readers: int = 0                  # streams actually being read: rooms on the same live stream share one
    rooms_by_kind: dict[str, int] = field(default_factory=dict)
    frames: int = 0
    frames_skipped: int = 0
    codes_found: int = 0
    cues_sent: int = 0
    cues_shown: int = 0
    watcher_failures: int = 0
    restarts_for_seek: int = 0
    last_poll_ok: bool = False
    skipped_sources: dict[str, int] = field(default_factory=dict)

    def as_dict(self) -> dict:
        return dict(self.__dict__)


class Supervisor:
    def __init__(self, api: BackendApi, config: Config, decode: Decode, frames: FrameSource,
                 clock: Callable[[], float] = time.monotonic, liveness: Liveness | None = None,
                 call_frames: CallSource | None = None) -> None:
        self._api = api
        self._config = config
        self._decode = decode
        self._frames = frames
        self._call_frames = call_frames
        self._clock = clock
        self._liveness = liveness or Liveness(clock=clock)
        self._dedupe = Dedupe(config.dedupe_minutes * 60, clock)
        self._watchers: dict[str, tuple[asyncio.Task, Plan]] = {}
        self._failures: dict[str, int] = {}
        self._retry_after: dict[str, float] = {}
        self.stats = Stats()
        self._streams = SharedStreams(decode, self.stats)

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
        for session_id in list(self._watchers):                          # rooms that ended (or stopped being ours)
            if session_id not in wanted:
                self._stop(session_id)
                self._dedupe.forget_session(session_id)
                self._failures.pop(session_id, None)
                self._retry_after.pop(session_id, None)
        for session_id, (task, _) in list(self._watchers.items()):      # watchers that stopped by themselves
            if task.done():
                del self._watchers[session_id]
        skipped: dict[str, int] = {}
        for session_id, session in wanted.items():
            plan = await self._plan(session)
            current = self._watchers.get(session_id)
            if isinstance(plan, str):                                    # nothing to watch now (paused, YouTube…)
                skipped[plan] = skipped.get(plan, 0) + 1
                if current:
                    self._stop(session_id)
                continue
            if current:
                if current[1].key == plan.key:
                    continue
                self._stop(session_id)                                   # a seek or a change of video: start again
                self.stats.restarts_for_seek += 1
                self._retry_after.pop(session_id, None)
            if len(self._watchers) >= self._config.max_rooms:
                log.warning("at the cap of %d rooms; add a shard", self._config.max_rooms)
                break
            if self._clock() < self._retry_after.get(session_id, 0.0):
                continue
            task = asyncio.create_task(self._watch(session, plan), name=f"watch-{session_id}")
            self._watchers[session_id] = (task, plan)
        self.stats.rooms = len(self._watchers)
        self.stats.readers = self._streams.readers
        kinds: dict[str, int] = {}
        for _, plan in self._watchers.values():
            kinds[plan.kind] = kinds.get(plan.kind, 0) + 1
        self.stats.rooms_by_kind = kinds
        self.stats.skipped_sources = skipped

    async def _plan(self, session: LiveSession) -> Plan | str:
        """How to watch this room now, or a short reason why not (shown on /health)."""
        if session.source is None:
            return "NOTHING_PLAYING"
        if session.source in DIRECT and session.stream_url:
            if await self._liveness.is_live(session.stream_url):
                return Plan("LIVE", ("live", session.stream_url))
            playback = session.playback
            if playback is None or playback.status != "PLAYING":
                return "PAUSED"
            position_ms = playback.position_now_ms(self._clock())
            if playback.duration_ms and position_ms >= playback.duration_ms:
                return "ENDED"
            return Plan("RECORDING", ("recording", session.stream_url, playback.version), position_ms / 1000)
        if session.screen_share and self._call_frames is not None:
            return Plan("CALL", ("call",))
        return f"{session.source}_NO_SCREEN_SHARE" if session.source in {"YOUTUBE", "VIMEO"} else session.source

    def _stop(self, session_id: str) -> None:
        current = self._watchers.pop(session_id, None)
        if current:
            current[0].cancel()

    async def stop_all(self) -> None:
        tasks = [task for task, _ in self._watchers.values()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self._watchers.clear()

    def _frames_for(self, session: LiveSession, plan: Plan) -> AsyncIterator[bytes]:
        cfg = self._config
        if plan.kind == "CALL":
            return self._call_frames(session, cfg.frame_width, cfg.frame_height, cfg.fps)
        return self._frames(session.stream_url, cfg.frame_width, cfg.frame_height, cfg.fps,
                            start_seconds=plan.start_seconds, realtime=plan.kind == "RECORDING")

    async def _watch(self, session: LiveSession, plan: Plan) -> None:
        started = self._clock()
        try:
            await self._read_and_decode(session, plan)
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

    def _stream_key(self, session: LiveSession, plan: Plan) -> tuple:
        """Rooms on the same live stream share one reader; a recording or a call is the room's own."""
        return plan.key if plan.kind == "LIVE" else (session.session_id, *plan.key)

    async def _read_and_decode(self, session: LiveSession, plan: Plan) -> None:
        async for codes in self._streams.codes(self._stream_key(session, plan), lambda: self._frames_for(session, plan)):
            for code in codes:
                self.stats.codes_found += 1
                if self._dedupe.is_new(session.session_id, code):
                    await self._report(session.session_id, code)

    async def _report(self, session_id: str, code: str) -> None:
        outcome = await self._api.report_cue(session_id, code)
        self.stats.cues_sent += 1
        if outcome and outcome.get("status") == "SHOWN":
            self.stats.cues_shown += 1
        log.info("session %s: QR reported, backend said %s", session_id, outcome)
