import sys
from pathlib import Path

import numpy as np
import pytest
import qrcode

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

WIDTH, HEIGHT = 960, 540


def frame_with_qr(text: str, size: int = 240, x: int = 600, y: int = 150) -> bytes:
    """A grey 960x540 frame, like ffmpeg gives us, with a QR code pasted in (as a church's slide would show it)."""
    canvas = np.full((HEIGHT, WIDTH), 90, dtype=np.uint8)
    qr = np.array(qrcode.make(text, border=2).convert("L").resize((size, size)), dtype=np.uint8)
    canvas[y:y + size, x:x + size] = qr
    return canvas.tobytes()


def blank_frame() -> bytes:
    return np.full((HEIGHT, WIDTH), 90, dtype=np.uint8).tobytes()


@pytest.fixture
def qr_frame():
    return frame_with_qr
