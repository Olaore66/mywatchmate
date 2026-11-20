import asyncio
import uuid

from conftest import HEIGHT, WIDTH, blank_frame, frame_with_qr

from worker.api import LiveSession
from worker.config import Config
from worker.decoder import find_qr_codes
from worker.supervisor import Supervisor

LINK = "https://business.payaza.africa/pay/nsppd"


class FakeLiveness:
    """Every .m3u8 is live; anything else is a recording."""

    async def is_live(self, url):
        return url.endswith(".m3u8")


class FakeBackend:
    def __init__(self, sessions):
        self.sessions = sessions
        self.cues = []
        self.fail = False

    async def live_sessions(self):
        if self.fail:
            raise RuntimeError("backend away")
        return self.sessions

    async def report_cue(self, session_id, code):
        self.cues.append((session_id, code))
        return {"status": "SHOWN"}


def live(source="URL", url="https://cdn/live.m3u8"):
    return LiveSession(str(uuid.uuid4()), str(uuid.uuid4()), source, url, False)


def config(**overrides):
    return Config(api_base="https://api.example", token="t", poll_seconds=0.01, **overrides)


async def decode(frame):
    return find_qr_codes(frame, WIDTH, HEIGHT)


def frames_of(*frames, then_wait=False):
    async def source(url, width, height, fps, start_seconds=None, realtime=False):
        for frame in frames:
            yield frame
            await asyncio.sleep(0)
        if then_wait:
            await asyncio.sleep(3600)
    return source


async def settle():
    for _ in range(50):
        await asyncio.sleep(0)


async def test_a_qr_code_in_a_live_room_is_reported_once():
    room = live()
    backend = FakeBackend([room])
    stream = frames_of(blank_frame(), frame_with_qr(LINK), frame_with_qr(LINK), then_wait=True)
    supervisor = Supervisor(backend, config(), decode, stream, liveness=FakeLiveness())

    await supervisor.reconcile_once()
    await settle()

    assert backend.cues == [(room.session_id, LINK)]
    assert supervisor.stats.rooms == 1
    assert supervisor.stats.cues_shown == 1
    await supervisor.stop_all()


async def test_only_live_direct_streams_are_watched():
    backend = FakeBackend([live("YOUTUBE", None), live("URL", "https://cdn/file.mp4"), live(None, None)])
    supervisor = Supervisor(backend, config(), decode, frames_of(then_wait=True), liveness=FakeLiveness())

    await supervisor.reconcile_once()

    assert supervisor.stats.rooms == 0
    assert supervisor.stats.skipped_sources == {"YOUTUBE_NO_SCREEN_SHARE": 1, "PAUSED": 1, "NOTHING_PLAYING": 1}


async def test_a_room_that_ends_loses_its_watcher_and_a_new_one_gets_one():
    first, second = live(), live()
    backend = FakeBackend([first])
    supervisor = Supervisor(backend, config(), decode, frames_of(then_wait=True), liveness=FakeLiveness())
    await supervisor.reconcile_once()

    backend.sessions = [second]
    await supervisor.reconcile_once()

    assert set(supervisor._watchers) == {second.session_id}
    await supervisor.stop_all()


async def test_shards_split_rooms_and_the_cap_holds():
    rooms = [live() for _ in range(20)]
    taken = set()
    for shard in range(3):
        supervisor = Supervisor(FakeBackend(rooms), config(shard=shard, shards=3), decode, frames_of(then_wait=True), liveness=FakeLiveness())
        await supervisor.reconcile_once()
        assert not taken & set(supervisor._watchers), "no room is watched twice"
        taken |= set(supervisor._watchers)
        await supervisor.stop_all()
    assert taken == {r.session_id for r in rooms}

    capped = Supervisor(FakeBackend(rooms), config(max_rooms=5), decode, frames_of(then_wait=True), liveness=FakeLiveness())
    await capped.reconcile_once()
    assert capped.stats.rooms == 5
    await capped.stop_all()


async def test_a_failing_stream_is_retried_later_not_straight_away():
    room = live()
    now = [0.0]

    def broken(url, width, height, fps, start_seconds=None, realtime=False):
        async def gen():
            raise ConnectionError("stream down")
            yield  # noqa: unreachable, makes this a generator
        return gen()

    supervisor = Supervisor(FakeBackend([room]), config(), decode, broken, clock=lambda: now[0], liveness=FakeLiveness())
    await supervisor.reconcile_once()
    await settle()
    await supervisor.reconcile_once()          # finished task cleared; but it must wait before retrying
    assert room.session_id not in supervisor._watchers
    assert supervisor.stats.watcher_failures == 1

    now[0] = 10.0
    await supervisor.reconcile_once()
    assert room.session_id in supervisor._watchers
    await supervisor.stop_all()


async def test_the_backend_being_away_doesnt_stop_anything():
    backend = FakeBackend([live()])
    supervisor = Supervisor(backend, config(), decode, frames_of(then_wait=True), liveness=FakeLiveness())
    await supervisor.reconcile_once()
    backend.fail = True

    await supervisor.reconcile_once()

    assert supervisor.stats.last_poll_ok is False
    assert supervisor.stats.rooms == 1, "rooms already watched keep being watched"
    await supervisor.stop_all()


async def test_when_decoding_is_slow_only_the_newest_frame_waits():
    room = live()
    backend = FakeBackend([room])
    release = asyncio.Event()
    decoded = []

    async def slow_decode(frame):
        decoded.append(frame)
        if len(decoded) == 1:
            await release.wait()
        return []

    frames = [bytes([i]) * (WIDTH * HEIGHT) for i in range(5)]
    supervisor = Supervisor(backend, config(), slow_decode, frames_of(*frames, then_wait=True), liveness=FakeLiveness())
    await supervisor.reconcile_once()
    await settle()
    release.set()
    await settle()

    assert decoded == [frames[0], frames[4]], "frames 1-3 were replaced while frame 0 was being read"
    assert supervisor.stats.frames_skipped == 3
    await supervisor.stop_all()
