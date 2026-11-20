"""One reader per stream, however many rooms watch it.

Many rooms often watch the same live stream (a popular church's morning service). Reading and decoding it once per
room would waste a reader (an ffmpeg, ~36 MB and a slice of a CPU) and a decode per frame for every extra room.
Instead, rooms watching the same stream key share one reader: each frame is decoded once and the codes found are
handed to every room watching. Each room still does its own dedupe and reports its own cue, so a room joining late,
or leaving, changes nothing for the others.

Rooms that can't share (a recording at the room's own position, a room's own call) simply use a key nobody else has.

The reader starts when the first room arrives and stops when the last one leaves. If it fails or ends, every room
watching hears about it (and is retried by the supervisor as usual); the next room to arrive starts a fresh reader.
The latest frame always wins: while one frame is being decoded, newer ones replace each other, so there's no backlog.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Protocol

log = logging.getLogger(__name__)

Decode = Callable[[bytes], Awaitable[list[str]]]
OpenFrames = Callable[[], AsyncIterator[bytes]]

_END = object()
QUEUE_LIMIT = 16   # codes waiting for one room; a room this far behind (its report is retrying) loses the oldest


class FrameStats(Protocol):
    frames: int
    frames_skipped: int


class _Stream:
    def __init__(self, open_frames: OpenFrames, decode: Decode, stats: FrameStats) -> None:
        self.rooms: set[asyncio.Queue] = set()
        self._open_frames = open_frames
        self._decode = decode
        self._stats = stats
        self._task = asyncio.create_task(self._run())

    @property
    def done(self) -> bool:
        return self._task.done()

    def stop(self) -> None:
        self._task.cancel()

    def _send(self, item) -> None:
        for queue in self.rooms:
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(item)

    async def _run(self) -> None:
        try:
            await self._read_and_decode()
            self._send(_END)
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 - passed on to every room watching
            self._send(e)

    async def _read_and_decode(self) -> None:
        latest: bytes | None = None
        finished = False
        ready = asyncio.Event()

        async def read() -> None:
            nonlocal latest, finished
            try:
                async for frame in self._open_frames():
                    if latest is not None:
                        self._stats.frames_skipped += 1
                    latest = frame
                    self._stats.frames += 1
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
                    codes = await self._decode(frame)
                    if codes:
                        self._send(codes)
                if finished and latest is None:
                    break
            await reader   # a reader that failed raises here
        finally:
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)


class SharedStreams:
    def __init__(self, decode: Decode, stats: FrameStats) -> None:
        self._decode = decode
        self._stats = stats
        self._streams: dict[tuple, _Stream] = {}

    @property
    def readers(self) -> int:
        return sum(1 for s in self._streams.values() if not s.done)

    async def codes(self, key: tuple, open_frames: OpenFrames) -> AsyncIterator[list[str]]:
        """The codes found on each frame of the stream with this key, read once for every room watching it."""
        stream = self._streams.get(key)
        if stream is None or stream.done:
            stream = _Stream(open_frames, self._decode, self._stats)
            self._streams[key] = stream
        queue: asyncio.Queue = asyncio.Queue(QUEUE_LIMIT)
        stream.rooms.add(queue)
        try:
            while True:
                item = await queue.get()
                if item is _END:
                    return
                if isinstance(item, BaseException):
                    raise item
                yield item
        finally:
            stream.rooms.discard(queue)
            if not stream.rooms and self._streams.get(key) is stream:
                del self._streams[key]
                stream.stop()
