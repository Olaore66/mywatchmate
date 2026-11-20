"""A QR code stays on screen for minutes; report it once per session per window, not every second."""

from __future__ import annotations

import time
from collections.abc import Callable


class Dedupe:
    def __init__(self, window_seconds: float, clock: Callable[[], float] = time.monotonic) -> None:
        self._window = window_seconds
        self._clock = clock
        self._last: dict[tuple[str, str], float] = {}

    def is_new(self, session_id: str, value: str) -> bool:
        """True (and remembered) the first time in the window; False for repeats."""
        now = self._clock()
        key = (session_id, value)
        last = self._last.get(key)
        if last is not None and now - last < self._window:
            return False
        self._last[key] = now
        return True

    def forget_session(self, session_id: str) -> None:
        """The session ended: drop what we remembered for it, so memory doesn't grow forever."""
        for key in [k for k in self._last if k[0] == session_id]:
            del self._last[key]
