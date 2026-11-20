"""Is a direct stream live? The backend can't tell from the link; the stream's own playlist can.

An HLS playlist (.m3u8) that is still being written has no #EXT-X-ENDLIST line; a finished recording has one. A
master playlist lists variants, so the first variant is checked. Anything else (an .mp4 file, an error) is not a
live stream as far as the worker is concerned: pre-recorded files need the room's play/pause/seek (a later step).
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from urllib.parse import urljoin

import httpx

log = logging.getLogger(__name__)

NOT_LIVE_RECHECK_SECONDS = 300.0
LIVE_RECHECK_SECONDS = 60.0


class Liveness:
    def __init__(self, client: httpx.AsyncClient | None = None, clock: Callable[[], float] = time.monotonic) -> None:
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(8.0, connect=4.0), follow_redirects=True)
        self._clock = clock
        self._not_live_until: dict[str, float] = {}
        self._live_until: dict[str, float] = {}

    async def is_live(self, url: str) -> bool:
        if not url.lower().split("?", 1)[0].endswith(".m3u8"):
            return False
        if self._clock() < self._not_live_until.get(url, 0.0):
            return False
        if self._clock() < self._live_until.get(url, 0.0):
            return True
        try:
            live = await self._check(url)
        except (httpx.HTTPError, ValueError) as e:
            log.info("couldn't read playlist %s: %s", url, e)
            live = False
        if live:
            self._live_until[url] = self._clock() + LIVE_RECHECK_SECONDS
        else:
            self._not_live_until[url] = self._clock() + NOT_LIVE_RECHECK_SECONDS
        return live

    async def _check(self, url: str) -> bool:
        playlist = await self._get(url)
        if "#EXT-X-STREAM-INF" in playlist:                    # a master playlist: look at its first variant
            variant = next((line.strip() for line in playlist.splitlines()
                            if line.strip() and not line.startswith("#")), None)
            if variant is None:
                return False
            playlist = await self._get(urljoin(url, variant))
        return "#EXTM3U" in playlist and "#EXT-X-ENDLIST" not in playlist

    async def _get(self, url: str) -> str:
        response = await self._client.get(url)
        response.raise_for_status()
        return response.text[:200_000]

    async def close(self) -> None:
        await self._client.aclose()
