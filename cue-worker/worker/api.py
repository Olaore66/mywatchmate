"""Talking to the MyWatchMate backend with our service token: which sessions are live, and reporting cues."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

log = logging.getLogger(__name__)

RETRIES = 3


@dataclass(frozen=True)
class Playback:
    """Where the room's video was when we asked (received_at: our own monotonic clock at that moment)."""
    status: str               # PLAYING, PAUSED or STOPPED
    position_ms: int
    duration_ms: int | None
    version: int
    received_at: float

    def position_now_ms(self, now: float) -> float:
        if self.status != "PLAYING":
            return float(self.position_ms)
        position = self.position_ms + (now - self.received_at) * 1000
        return min(position, self.duration_ms) if self.duration_ms else position

    @staticmethod
    def from_json(data: dict | None, received_at: float) -> "Playback | None":
        if not data:
            return None
        return Playback(str(data.get("status")), int(data.get("positionMs") or 0), data.get("durationMs"),
                        int(data.get("version") or 0), received_at)


@dataclass(frozen=True)
class LiveSession:
    session_id: str
    space_id: str
    source: str | None        # YOUTUBE, VIMEO, URL, LIVESTREAM, or None when nothing is playing
    stream_url: str | None    # set only for streams we may read ourselves
    screen_share: bool
    playback: Playback | None = None

    @staticmethod
    def from_json(data: dict, received_at: float = 0.0) -> "LiveSession":
        return LiveSession(str(data["sessionId"]), str(data["spaceId"]), data.get("source"), data.get("streamUrl"),
                           bool(data.get("screenShare")), Playback.from_json(data.get("playback"), received_at))


class BackendApi:
    def __init__(self, base: str, token: str, client: httpx.AsyncClient | None = None) -> None:
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(10.0, connect=5.0))
        self._base = base.rstrip("/")
        self._headers = {"X-Service-Token": token, "User-Agent": "mywatchmate-cue-worker"}

    async def live_sessions(self) -> list[LiveSession]:
        response = await self._client.get(f"{self._base}/api/v1/internal/live-sessions", headers=self._headers)
        response.raise_for_status()
        received_at = time.monotonic()
        return [LiveSession.from_json(item, received_at) for item in response.json()]

    async def watch_token(self, session_id: str) -> dict:
        """A hidden, view-only way into a live session's call: {url, token, roomName, identity, expiresAt}."""
        response = await self._client.post(f"{self._base}/api/v1/internal/sessions/{session_id}/watch-token",
                                           headers=self._headers)
        response.raise_for_status()
        return response.json()

    async def report_cue(self, session_id: str, raw_value: str) -> dict | None:
        """Sends one cue. Retries network trouble and 5xx/429 with a growing wait; other refusals are final.

        The backend decides everything (a real Payaza link? blocked? shown recently?) and says what happened.
        """
        body = {"sessionId": session_id, "rawValue": raw_value[:4096], "cueType": "QR"}
        for attempt in range(1, RETRIES + 1):
            try:
                response = await self._client.post(f"{self._base}/api/v1/internal/cues", json=body, headers=self._headers)
            except httpx.TransportError as e:
                log.warning("cue for %s not sent (attempt %d): %s", session_id, attempt, e)
            else:
                if response.status_code < 300:
                    return response.json()
                if response.status_code != 429 and response.status_code < 500:
                    log.info("cue for %s refused: %s %s", session_id, response.status_code, response.text[:200])
                    return None
                log.warning("cue for %s got %s (attempt %d)", session_id, response.status_code, attempt)
            if attempt < RETRIES:
                await asyncio.sleep(2 ** attempt)
        return None

    async def close(self) -> None:
        await self._client.aclose()
