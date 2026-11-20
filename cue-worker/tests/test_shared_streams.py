"""Rooms watching the same live stream share one reader and one decode per frame."""

import asyncio
import uuid

from conftest import HEIGHT, WIDTH, blank_frame, frame_with_qr
from test_supervisor import FakeBackend, FakeLiveness, config, settle

from worker.api import LiveSession, Playback
from worker.decoder import find_qr_codes
from worker.supervisor import Supervisor

LINK = "https://business.payaza.africa/pay/nsppd"
STREAM = "https://cdn/nsppd/live.m3u8"


def room(url=STREAM, playback=None):
    return LiveSession(str(uuid.uuid4()), str(uuid.uuid4()), "URL", url, False, playback)


class CountingSource:
    """A frame source that counts how many readers were opened and closed, and lets the test feed frames in."""

    def __init__(self):
        self.opened: list[str] = []
        self.closed = 0
        self.feed: asyncio.Queue = asyncio.Queue()

    def __call__(self, url, width, height, fps, start_seconds=None, realtime=False):
        self.opened.append(url)
        return self._frames()

    async def _frames(self):
        try:
            while True:
                frame = await self.feed.get()
                if isinstance(frame, Exception):
                    raise frame
                yield frame
        finally:
            self.closed += 1


class CountingDecode:
    def __init__(self):
        self.calls = 0

    async def __call__(self, frame):
        self.calls += 1
        return find_qr_codes(frame, WIDTH, HEIGHT)


async def test_rooms_on_the_same_stream_share_one_reader_and_each_gets_its_cue():
    rooms = [room() for _ in range(5)]
    backend, source, decode = FakeBackend(rooms), CountingSource(), CountingDecode()
    supervisor = Supervisor(backend, config(), decode, source, liveness=FakeLiveness())

    await supervisor.reconcile_once()
    await settle()
    for frame in (blank_frame(), frame_with_qr(LINK), frame_with_qr(LINK)):
        source.feed.put_nowait(frame)
        await settle()
    await supervisor.reconcile_once()

    assert source.opened == [STREAM], "one reader for five rooms"
    assert decode.calls == 3, "each frame decoded once, not once per room"
    assert sorted(backend.cues) == sorted((r.session_id, LINK) for r in rooms), "every room reports its own cue, once"
    assert supervisor.stats.rooms == 5 and supervisor.stats.readers == 1
    await supervisor.stop_all()
    await settle()
    assert source.closed == 1


async def test_different_streams_get_their_own_readers():
    rooms = [room(), room(), room("https://cdn/other/live.m3u8")]
    source = CountingSource()
    supervisor = Supervisor(FakeBackend(rooms), config(), CountingDecode(), source, liveness=FakeLiveness())

    await supervisor.reconcile_once()
    await settle()
    await supervisor.reconcile_once()

    assert sorted(source.opened) == sorted([STREAM, "https://cdn/other/live.m3u8"])
    assert supervisor.stats.readers == 2
    await supervisor.stop_all()


async def test_the_reader_keeps_going_while_any_room_watches_and_stops_with_the_last():
    first, second = room(), room()
    backend, source = FakeBackend([first, second]), CountingSource()
    supervisor = Supervisor(backend, config(), CountingDecode(), source, liveness=FakeLiveness())
    await supervisor.reconcile_once()
    await settle()

    backend.sessions = [second]                     # the first room ends
    await supervisor.reconcile_once()
    await settle()
    assert source.closed == 0, "the second room is still watching"
    source.feed.put_nowait(frame_with_qr(LINK))
    await settle()
    assert backend.cues == [(second.session_id, LINK)]

    backend.sessions = []                           # the last room ends
    await supervisor.reconcile_once()
    await settle()
    assert source.closed == 1 and len(source.opened) == 1


async def test_a_room_joining_later_uses_the_reader_already_running():
    early, late = room(), room()
    backend, source = FakeBackend([early]), CountingSource()
    supervisor = Supervisor(backend, config(), CountingDecode(), source, liveness=FakeLiveness())
    await supervisor.reconcile_once()
    await settle()

    backend.sessions = [early, late]
    await supervisor.reconcile_once()
    await settle()
    source.feed.put_nowait(frame_with_qr(LINK))
    await settle()

    assert len(source.opened) == 1
    assert {s for s, _ in backend.cues} == {early.session_id, late.session_id}
    await supervisor.stop_all()


async def test_when_the_shared_reader_fails_every_room_on_it_is_retried():
    rooms = [room() for _ in range(3)]
    source = CountingSource()
    supervisor = Supervisor(FakeBackend(rooms), config(), CountingDecode(), source, liveness=FakeLiveness())
    await supervisor.reconcile_once()
    await settle()

    source.feed.put_nowait(ConnectionError("stream down"))
    await settle()

    assert supervisor.stats.watcher_failures == 3
    await supervisor.reconcile_once()
    assert supervisor.stats.rooms == 0, "all three wait before trying again"
    await supervisor.stop_all()


async def test_recordings_are_never_shared_each_room_reads_from_its_own_place():
    playing = Playback("PLAYING", 60_000, 3_600_000, 1, 0.0)
    rooms = [room("https://cdn/sermon.mp4", playing), room("https://cdn/sermon.mp4", playing)]
    source = CountingSource()
    supervisor = Supervisor(FakeBackend(rooms), config(), CountingDecode(), source, clock=lambda: 0.0,
                            liveness=FakeLiveness())

    await supervisor.reconcile_once()
    await settle()

    assert source.opened == ["https://cdn/sermon.mp4"] * 2
    await supervisor.stop_all()
