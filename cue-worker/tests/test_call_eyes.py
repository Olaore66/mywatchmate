import httpx
import numpy as np
from conftest import HEIGHT, WIDTH

from worker.api import BackendApi
from worker.call_eyes import fit_grey
from worker.config import Config
from worker.decoder import find_qr_codes

LINK = "https://business.payaza.africa/pay/nsppd"


def test_a_full_hd_screen_is_shrunk_to_fit_and_a_qr_on_it_still_reads():
    import qrcode
    screen = np.full((1080, 1920), 200, dtype=np.uint8)
    qr = np.array(qrcode.make(LINK, border=2).convert("L").resize((420, 420)), dtype=np.uint8)
    screen[300:720, 1300:1720] = qr

    frame = fit_grey(screen, WIDTH, HEIGHT)

    assert len(frame) == WIDTH * HEIGHT
    assert find_qr_codes(frame, WIDTH, HEIGHT) == [LINK]


def test_a_screen_with_another_shape_is_centred_on_black_and_small_ones_are_not_enlarged():
    tall = np.full((1000, 500), 255, dtype=np.uint8)
    out = np.frombuffer(fit_grey(tall, WIDTH, HEIGHT), dtype=np.uint8).reshape(HEIGHT, WIDTH)
    assert out[:, 0].max() == 0 and out[HEIGHT // 2, WIDTH // 2] == 255

    small = np.full((100, 200), 255, dtype=np.uint8)
    out = np.frombuffer(fit_grey(small, WIDTH, HEIGHT), dtype=np.uint8).reshape(HEIGHT, WIDTH)
    assert int((out == 255).sum()) == 100 * 200


async def test_the_watch_token_is_asked_for_with_the_service_token():
    def handler(request):
        assert request.method == "POST"
        assert request.url.path == "/api/v1/internal/sessions/s1/watch-token"
        assert request.headers["X-Service-Token"] == "secret"
        return httpx.Response(200, json={"url": "wss://calls", "token": "t", "roomName": "space-r1", "identity": "cue-worker"})

    api = BackendApi("https://api.example", "secret", httpx.AsyncClient(transport=httpx.MockTransport(handler)))

    assert (await api.watch_token("s1"))["url"] == "wss://calls"


def test_watching_calls_can_be_switched_off():
    assert Config.from_env({"INTERNAL_CUE_TOKEN": "x"}).watch_calls
    assert not Config.from_env({"INTERNAL_CUE_TOKEN": "x", "WATCH_CALLS": "false"}).watch_calls


# --- with the real LiveKit library: real I420 frames, and the join/subscribe/read steps against a pretend call ---

import asyncio  # noqa: E402
import uuid  # noqa: E402

import pytest  # noqa: E402

rtc = pytest.importorskip("livekit.rtc")

from worker.api import LiveSession  # noqa: E402
from worker.call_eyes import CallEyes, y_plane  # noqa: E402


def i420(grey: np.ndarray) -> "rtc.VideoFrame":
    h, w = grey.shape
    chroma = np.full(((h + 1) // 2) * ((w + 1) // 2) * 2, 128, dtype=np.uint8)
    return rtc.VideoFrame(w, h, rtc.VideoBufferType.I420, bytearray(grey.tobytes() + chroma.tobytes()))


def screen_with_qr() -> np.ndarray:
    import qrcode
    screen = np.full((1080, 1920), 200, dtype=np.uint8)
    screen[300:720, 1300:1720] = np.array(qrcode.make(LINK, border=2).convert("L").resize((420, 420)), dtype=np.uint8)
    return screen


def test_the_grey_layer_of_a_real_livekit_frame_is_the_screen():
    screen = screen_with_qr()
    assert np.array_equal(y_plane(i420(screen)), screen)
    assert find_qr_codes(fit_grey(y_plane(i420(screen)), WIDTH, HEIGHT), WIDTH, HEIGHT) == [LINK]


class Publication:
    def __init__(self, source, kind):
        self.source, self.kind, self.subscribed = source, kind, False

    def set_subscribed(self, value):
        self.subscribed = value


class Participant:
    def __init__(self, *publications):
        self.track_publications = {str(i): p for i, p in enumerate(publications)}


class PretendRoom:
    """Stands in for rtc.Room: a host who is sharing their screen, has a camera on and a microphone."""
    last = None

    def __init__(self):
        PretendRoom.last = self
        self.handlers = {}
        self.share = Publication(rtc.TrackSource.SOURCE_SCREENSHARE, rtc.TrackKind.KIND_VIDEO)
        self.camera = Publication(rtc.TrackSource.SOURCE_CAMERA, rtc.TrackKind.KIND_VIDEO)
        self.mic = Publication(rtc.TrackSource.SOURCE_MICROPHONE, rtc.TrackKind.KIND_AUDIO)
        self.remote_participants = {"host": Participant(self.camera, self.mic, self.share)}
        self.connected_with = None
        self.disconnected = False

    def on(self, event, callback=None):
        def register(fn):
            self.handlers[event] = fn
            return fn
        return register(callback) if callback else register

    async def connect(self, url, token, options=None):
        self.connected_with = (url, token, options.auto_subscribe)

    async def disconnect(self):
        self.disconnected = True


class PretendStream:
    """Stands in for rtc.VideoStream: a 30 fps screen share."""
    frames = []

    def __init__(self, track, capacity=0, format=None):
        assert format == rtc.VideoBufferType.I420
        self.closed = False
        PretendStream.last = self

    def __aiter__(self):
        return self._events()

    async def _events(self):
        for frame in PretendStream.frames:
            yield type("Event", (), {"frame": frame})()
            await asyncio.sleep(1 / 30)

    async def aclose(self):
        self.closed = True


class TokenApi:
    async def watch_token(self, session_id):
        return {"url": "wss://calls.example", "token": "hidden-view-only", "roomName": "r", "identity": "cue-worker"}


@pytest.fixture
def pretend_call(monkeypatch):
    monkeypatch.setattr(rtc, "Room", PretendRoom)
    monkeypatch.setattr(rtc, "VideoStream", PretendStream)

    original_set = Publication.set_subscribed

    def subscribe_and_deliver(self, value):
        original_set(self, value)
        room = PretendRoom.last
        if value and self is room.share:
            room.handlers["track_subscribed"]("share-track", self, None)

    monkeypatch.setattr(Publication, "set_subscribed", subscribe_and_deliver)


def session():
    return LiveSession(str(uuid.uuid4()), str(uuid.uuid4()), "YOUTUBE", None, True)


async def test_the_worker_joins_hidden_reads_only_the_screen_share_and_finds_the_code(pretend_call):
    PretendStream.frames = [i420(screen_with_qr())] * 45          # 1.5 s of a 30 fps share

    import time
    started = time.monotonic()
    frames = [f async for f in CallEyes(TokenApi())(session(), WIDTH, HEIGHT, 1.0)]
    seconds = time.monotonic() - started

    room = PretendRoom.last
    assert room.connected_with == ("wss://calls.example", "hidden-view-only", False), "nothing subscribed by itself"
    assert room.share.subscribed and not room.camera.subscribed and not room.mic.subscribed
    assert 1 <= len(frames) <= int(seconds) + 1, f"about one frame a second, not 30 ({len(frames)} in {seconds:.1f}s)"
    assert find_qr_codes(frames[0], WIDTH, HEIGHT) == [LINK]
    assert PretendStream.last.closed and room.disconnected, "leaves the call when done"


async def test_with_no_screen_share_the_worker_leaves_the_call(pretend_call, monkeypatch):
    import worker.call_eyes as call_eyes
    monkeypatch.setattr(call_eyes, "WAIT_FOR_SHARE_SECONDS", 0.05)

    class NoShareRoom(PretendRoom):
        def __init__(self):
            super().__init__()
            self.remote_participants = {"host": Participant(self.camera, self.mic)}

    monkeypatch.setattr(rtc, "Room", NoShareRoom)

    frames = [f async for f in CallEyes(TokenApi())(session(), WIDTH, HEIGHT, 1.0)]

    assert frames == [] and PretendRoom.last.disconnected
