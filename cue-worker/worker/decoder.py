"""Finds QR codes in one grey frame. Runs in a separate process (image work would hold up everything else)."""

from __future__ import annotations

import numpy as np
import zxingcpp


def find_qr_codes(frame: bytes, width: int, height: int) -> list[str]:
    """The text of every QR code in the frame (usually none). Never raises: a bad frame just has no codes."""
    if len(frame) != width * height:
        return []
    image = np.frombuffer(frame, dtype=np.uint8).reshape(height, width)
    try:
        results = zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode)
    except Exception:  # noqa: BLE001 - a decoder failure must never stop a room being watched
        return []
    seen: list[str] = []
    for result in results:
        text = result.text.strip()
        if text and text not in seen:
            seen.append(text)
    return seen
