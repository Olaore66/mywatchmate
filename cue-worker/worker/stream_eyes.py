"""Frames from a live stream, through ffmpeg: keyframes only, about once a second, small and grey.

Decoding only keyframes means the video is never fully decoded, so each room costs a small slice of a CPU. A
giving QR code stays on screen for many seconds, so nothing is missed.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator


def ffmpeg_command(url: str, width: int, height: int, fps: float) -> list[str]:
    return [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
        "-rw_timeout", "15000000",                     # give up on a stream silent for 15 s (the supervisor retries)
        "-skip_frame", "nokey",
        "-i", url,
        "-an", "-sn",                                  # no audio or subtitles
        "-vf", f"fps={fps},scale={width}:{height}:force_original_aspect_ratio=decrease,"
               f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,format=gray",
        "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1",
    ]


async def stream_frames(url: str, width: int, height: int, fps: float) -> AsyncIterator[bytes]:
    """Yields frames until the stream ends or fails; always stops ffmpeg when it's done."""
    process = await asyncio.create_subprocess_exec(
        *ffmpeg_command(url, width, height, fps),
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    size = width * height
    try:
        while True:
            try:
                yield await process.stdout.readexactly(size)
            except asyncio.IncompleteReadError:
                return
    finally:
        if process.returncode is None:
            process.kill()
            await process.wait()
