"""Frames from a room's call: for YouTube/Vimeo rooms, the worker reads the host's screen share instead of the video.

The worker joins with a hidden, view-only token from the backend (never shown in the room, can't send anything), and
subscribes ONLY to screen-share video: no cameras, no audio. About once a second it takes a frame's brightness layer
(the Y plane of I420 is already grey), shrinks it to the worker's frame size and hands it to the QR reader.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator

import numpy as np

from .api import BackendApi, LiveSession

log = logging.getLogger(__name__)

WAIT_FOR_SHARE_SECONDS = 20.0


def fit_grey(y: np.ndarray, width: int, height: int) -> bytes:
    """Shrinks (or keeps) a grey image to fit width x height, centred on black, like ffmpeg's scale+pad."""
    h, w = y.shape
    scale = min(width / w, height / h, 1.0)
    new_w, new_h = max(1, int(w * scale)), max(1, int(h * scale))
    rows = np.minimum((np.arange(new_h) / scale).astype(np.int64), h - 1)
    cols = np.minimum((np.arange(new_w) / scale).astype(np.int64), w - 1)
    canvas = np.zeros((height, width), dtype=np.uint8)
    top, left = (height - new_h) // 2, (width - new_w) // 2
    canvas[top:top + new_h, left:left + new_w] = y[rows][:, cols]
    return canvas.tobytes()


def y_plane(frame) -> np.ndarray:
    """The brightness (grey) layer of an I420 frame, as height x width. Rows may be padded, so use the stride."""
    plane = np.frombuffer(frame.get_plane(0), dtype=np.uint8)
    stride = len(plane) // frame.height
    return plane[:stride * frame.height].reshape(frame.height, stride)[:, :frame.width]


class CallEyes:
    def __init__(self, api: BackendApi) -> None:
        self._api = api

    def __call__(self, session: LiveSession, width: int, height: int, fps: float) -> AsyncIterator[bytes]:
        return self._frames(session, width, height, fps)

    async def _frames(self, session: LiveSession, width: int, height: int, fps: float) -> AsyncIterator[bytes]:
        from livekit import rtc  # imported here so the rest of the worker runs without LiveKit installed

        grant = await self._api.watch_token(session.session_id)
        room = rtc.Room()
        shares: asyncio.Queue = asyncio.Queue()

        def want(publication) -> bool:
            return publication.source == rtc.TrackSource.SOURCE_SCREENSHARE and publication.kind == rtc.TrackKind.KIND_VIDEO

        @room.on("track_published")
        def on_published(publication, participant):
            if want(publication):
                publication.set_subscribed(True)

        @room.on("track_subscribed")
        def on_subscribed(track, publication, participant):
            if want(publication):
                shares.put_nowait(track)

        await room.connect(grant["url"], grant["token"], options=rtc.RoomOptions(auto_subscribe=False))
        stream = None
        try:
            for participant in room.remote_participants.values():
                for publication in participant.track_publications.values():
                    if want(publication):
                        publication.set_subscribed(True)
            try:
                track = await asyncio.wait_for(shares.get(), timeout=WAIT_FOR_SHARE_SECONDS)
            except TimeoutError:
                log.info("session %s: no screen share to watch", session.session_id)
                return
            stream = rtc.VideoStream(track, capacity=2, format=rtc.VideoBufferType.I420)  # keep only the newest
            interval = 1.0 / fps
            next_at = 0.0
            async for event in stream:
                now = time.monotonic()
                if now < next_at:
                    continue
                next_at = now + interval
                yield fit_grey(y_plane(event.frame), width, height)
        finally:
            if stream is not None:
                await stream.aclose()
            await room.disconnect()
