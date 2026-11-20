"""Recordings follow the room (play, pause, seek); YouTube/Vimeo rooms are watched through a screen share."""

import asyncio
import uuid

from conftest import HEIGHT, WIDTH, frame_with_qr

from worker.api import LiveSession, Playback
from worker.config import Config
from worker.decoder import find_qr_codes
from worker.supervisor import Supervisor

LINK = "https://business.payaza.africa/pay/nsppd"
FILE = "https://cdn/sermon.mp4"


class Backend:
    def __init__(self, sessions):
        self.sessions = sessions
        self.cues = []

    async def live_sessions(self):
        return self.sessions

    async def report_cue(self, session_id, code):
        self.cues.append((session_id, code))
        return {"status": "SHOWN"}


class NeverLive:
    async def is_live(self, url):
        return False


class RecordingSource:
    """Remembers where each read started, and keeps 'streaming' until stopped."""

    def __init__(self):
        self.starts = []

    def __call__(self, url, width, height, fps, start_seconds=None, realtime=False):
        self.starts.append((start_seconds, realtime))

        async def frames():
            await asyncio.sleep(3600)
            yield b""
        return frames()


def room(session_id, status="PLAYING", position_ms=600_000, version=1, duration_ms=3_600_000, received_at=0.0,
         source="URL", url=FILE, screen_share=False):
    playback = Playback(status, position_ms, duration_ms, version, received_at) if status else None
    return LiveSession(session_id, str(uuid.uuid4()), source, url, screen_share, playback)


def config():
    return Config(api_base="https://api.example", token="t", poll_seconds=0.01)


async def decode(frame):
    return find_qr_codes(frame, WIDTH, HEIGHT) if frame else []


async def settle():
    for _ in range(30):
        await asyncio.sleep(0)


async def test_a_recording_is_read_from_where_the_room_is_in_real_time():
    sid = str(uuid.uuid4())
    now = [12.5]
    source = RecordingSource()
    supervisor = Supervisor(Backend([room(sid, position_ms=600_000, received_at=10.0)]), config(), decode, source,
                            clock=lambda: now[0], liveness=NeverLive())

    await supervisor.reconcile_once()
    await settle()

    start, realtime = source.starts[0]
    assert abs(start - 602.5) < 0.01, "10 minutes in, plus the 2.5 s since we asked"
    assert realtime
    assert supervisor.stats.rooms_by_kind == {"RECORDING": 1}
    await supervisor.stop_all()


async def test_a_seek_starts_again_from_the_new_spot_and_a_pause_stops_reading():
    sid = str(uuid.uuid4())
    backend = Backend([room(sid, position_ms=60_000, version=1)])
    source = RecordingSource()
    supervisor = Supervisor(backend, config(), decode, source, clock=lambda: 0.0, liveness=NeverLive())
    await supervisor.reconcile_once()
    await supervisor.reconcile_once()
    await settle()
    assert len(source.starts) == 1, "nothing changed, so the same watcher carries on"

    backend.sessions = [room(sid, position_ms=1_800_000, version=2)]
    await supervisor.reconcile_once()
    await settle()
    assert source.starts[-1][0] == 1800.0
    assert supervisor.stats.restarts_for_seek == 1
    assert supervisor.stats.watcher_failures == 0, "a seek isn't a failure"

    backend.sessions = [room(sid, status="PAUSED", position_ms=1_800_000, version=3)]
    await supervisor.reconcile_once()
    assert supervisor.stats.rooms == 0
    assert supervisor.stats.skipped_sources == {"PAUSED": 1}


async def test_a_finished_recording_or_a_room_with_no_state_is_left_alone():
    ended = room(str(uuid.uuid4()), position_ms=3_600_000)
    unknown = room(str(uuid.uuid4()), status=None)
    supervisor = Supervisor(Backend([ended, unknown]), config(), decode, RecordingSource(), clock=lambda: 0.0,
                            liveness=NeverLive())

    await supervisor.reconcile_once()

    assert supervisor.stats.skipped_sources == {"ENDED": 1, "PAUSED": 1}


async def test_a_youtube_room_with_a_screen_share_is_watched_through_the_call():
    sid = str(uuid.uuid4())
    seen = []

    def call_frames(session, width, height, fps):
        seen.append(session.session_id)

        async def frames():
            yield frame_with_qr(LINK)
            await asyncio.sleep(3600)
        return frames()

    backend = Backend([room(sid, source="YOUTUBE", url=None, screen_share=True),
                       room(str(uuid.uuid4()), source="YOUTUBE", url=None, screen_share=False)])
    supervisor = Supervisor(backend, config(), decode, RecordingSource(), liveness=NeverLive(), call_frames=call_frames)

    await supervisor.reconcile_once()
    await settle()

    assert seen == [sid]
    assert backend.cues == [(sid, LINK)]
    assert supervisor.stats.rooms_by_kind == {"CALL": 1}
    assert supervisor.stats.skipped_sources == {"YOUTUBE_NO_SCREEN_SHARE": 1}
    await supervisor.stop_all()


async def test_when_the_screen_share_stops_the_call_watcher_stops():
    sid = str(uuid.uuid4())

    def call_frames(session, width, height, fps):
        async def frames():
            await asyncio.sleep(3600)
            yield b""
        return frames()

    backend = Backend([room(sid, source="YOUTUBE", url=None, screen_share=True)])
    supervisor = Supervisor(backend, config(), decode, RecordingSource(), liveness=NeverLive(), call_frames=call_frames)
    await supervisor.reconcile_once()
    assert supervisor.stats.rooms == 1

    backend.sessions = [room(sid, source="YOUTUBE", url=None, screen_share=False)]
    await supervisor.reconcile_once()

    assert supervisor.stats.rooms == 0


def test_where_the_room_is_moves_while_playing_and_stays_while_paused():
    playing = Playback("PLAYING", 1_000, 5_000, 1, received_at=10.0)
    assert playing.position_now_ms(12.0) == 3_000
    assert playing.position_now_ms(99.0) == 5_000, "never past the end"
    assert Playback("PAUSED", 1_000, None, 1, received_at=10.0).position_now_ms(99.0) == 1_000
    assert Playback.from_json(None, 0.0) is None
    assert Playback.from_json({"status": "PLAYING", "positionMs": 5, "durationMs": None, "version": 2}, 1.0).version == 2
