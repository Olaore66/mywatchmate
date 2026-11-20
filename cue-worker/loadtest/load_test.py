"""Load test: one worker watching N rooms at once, all live, all showing a giving QR code.

The real pieces run: the supervisor, one ffmpeg reader per room, the decoding processes and the dedupe.
Only the backend is pretend (it lists N live rooms and records the cues), so the numbers are the worker's own.

    cd cue-worker
    .venv/Scripts/python -m pip install psutil
    .venv/Scripts/python loadtest/load_test.py --rooms 50 --seconds 90

It makes a live HLS stream with ffmpeg (1280x720, keyframe every second, QR code always on screen), serves it on
http://127.0.0.1:<port>/live.m3u8, points every room at it, and reports: rooms watched, frames read and decoded per
second, frames dropped because decoding was behind, time until each room's cue was sent, and CPU and memory used.
"""

from __future__ import annotations

import argparse
import asyncio
import functools
import http.server
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import psutil
import qrcode

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from worker.api import LiveSession  # noqa: E402
from worker.config import Config  # noqa: E402
from worker.decoder import find_qr_codes  # noqa: E402
from worker.liveness import Liveness  # noqa: E402
from worker.stream_eyes import stream_frames  # noqa: E402
from worker.supervisor import Supervisor  # noqa: E402

LINK = "https://business.payaza.africa/pay/load-test"


class PretendBackend:
    def __init__(self, sessions: list[LiveSession]) -> None:
        self._sessions = sessions
        self.cues: dict[str, float] = {}

    async def live_sessions(self) -> list[LiveSession]:
        return self._sessions

    async def report_cue(self, session_id: str, raw_value: str) -> dict:
        assert raw_value == LINK
        self.cues.setdefault(session_id, time.monotonic())
        return {"status": "SHOWN"}

    async def watch_token(self, session_id: str) -> dict:
        raise RuntimeError("no calls in the load test")

    async def close(self) -> None:
        pass


def start_live_stream(folder: Path) -> subprocess.Popen:
    qr_png = folder / "qr.png"
    qrcode.make(LINK, border=2).resize((300, 300)).save(qr_png)
    return subprocess.Popen([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
        "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=25",
        "-i", str(qr_png),
        "-filter_complex", "[0][1]overlay=x=900:y=360",
        "-c:v", "libx264", "-preset", "veryfast", "-g", "25", "-pix_fmt", "yuv420p",
        "-f", "hls", "-hls_time", "2", "-hls_list_size", "6", "-hls_flags", "delete_segments",
        str(folder / "live.m3u8"),
    ])


def serve(folder: Path) -> tuple[http.server.ThreadingHTTPServer, int]:
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(folder)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


def usage(me: psutil.Process, exclude: int) -> tuple[float, int, int]:
    """CPU seconds and memory of this process and its children (ffmpeg readers, decoders), and the reader count.
    The ffmpeg making the test stream (exclude) isn't the worker, so it isn't counted."""
    cpu, rss, readers = 0.0, 0, 0
    for p in [me, *me.children(recursive=True)]:
        if p.pid == exclude:
            continue
        try:
            t = p.cpu_times()
            cpu += t.user + t.system
            rss += p.memory_info().rss
            readers += p.name().lower().startswith("ffmpeg")
        except psutil.Error:
            pass
    return cpu, rss, readers


async def main(rooms: int, seconds: float, decoders: int) -> None:
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg isn't installed")
    folder = Path(tempfile.mkdtemp(prefix="cue-load-"))
    source = start_live_stream(folder)
    server, port = serve(folder)
    url = f"http://127.0.0.1:{port}/live.m3u8"
    for _ in range(40):
        if (folder / "live.m3u8").exists():
            break
        await asyncio.sleep(0.5)
    await asyncio.sleep(4)  # a few segments, so every reader can start straight away

    sessions = [LiveSession(str(uuid.uuid4()), str(uuid.uuid4()), "URL", url, False) for _ in range(rooms)]
    backend = PretendBackend(sessions)
    config = Config(api_base="http://pretend", token="x", max_rooms=rooms, poll_seconds=5, decoders=decoders)
    loop = asyncio.get_running_loop()
    pool = ProcessPoolExecutor(max_workers=decoders)
    decode_seconds: list[float] = []

    async def decode(frame: bytes) -> list[str]:
        started = time.perf_counter()
        codes = await loop.run_in_executor(pool, find_qr_codes, frame, config.frame_width, config.frame_height)
        decode_seconds.append(time.perf_counter() - started)
        return codes

    await loop.run_in_executor(pool, find_qr_codes, b"\0" * (960 * 540), 960, 540)  # warm the decoders up
    liveness = Liveness()
    supervisor = Supervisor(backend, config, decode, stream_frames, liveness=liveness)
    me = psutil.Process()
    measure = functools.partial(usage, me, source.pid)
    cpu0, rss0, _ = measure()
    started = time.monotonic()
    stop = asyncio.Event()
    run = asyncio.create_task(supervisor.run(stop))

    peak_rss, peak_readers, samples = 0, 0, []
    warmup = 15.0
    frames_at_warmup = decoded_at_warmup = None
    cpu_at_warmup = 0.0
    while time.monotonic() - started < seconds:
        await asyncio.sleep(5)
        elapsed = time.monotonic() - started
        cpu, rss, readers = measure()
        peak_rss, peak_readers = max(peak_rss, rss), max(peak_readers, readers)
        if frames_at_warmup is None and elapsed >= warmup:
            frames_at_warmup, decoded_at_warmup, cpu_at_warmup = supervisor.stats.frames, len(decode_seconds), cpu
        s = supervisor.stats
        samples.append(f"  {elapsed:5.0f}s  rooms {s.rooms:3d}  readers {s.readers:2d}  ffmpeg {readers:3d}  frames {s.frames:6d}  "
                       f"dropped {s.frames_skipped:5d}  cues {len(backend.cues):3d}  memory {rss / 2**20:6.0f} MB")
        print(samples[-1], flush=True)

    end = time.monotonic()
    cpu_end, _, _ = measure()
    s = supervisor.stats
    stop.set()
    await run
    await liveness.close()
    pool.shutdown(cancel_futures=True)
    source.terminate()
    server.shutdown()
    shutil.rmtree(folder, ignore_errors=True)

    steady = end - started - warmup
    to_cue = sorted(t - started for t in backend.cues.values())
    cores = psutil.cpu_count(logical=True)
    print()
    print(f"Rooms: {rooms}, decoders: {decoders}, machine: {cores} logical CPUs, {seconds:.0f}s run")
    print(f"Rooms with a cue sent: {len(backend.cues)}/{rooms}"
          + (f" (first after {to_cue[0]:.1f}s, median {statistics.median(to_cue):.1f}s, last {to_cue[-1]:.1f}s)"
             if to_cue else ""))
    print(f"Frames read: {s.frames} ({(s.frames - frames_at_warmup) / steady:.1f}/s steady), decoded "
          f"{len(decode_seconds)} ({(len(decode_seconds) - decoded_at_warmup) / steady:.1f}/s), "
          f"dropped while decoding was busy: {s.frames_skipped}")
    if decode_seconds:
        ds = sorted(decode_seconds)
        print(f"Decode wait (queue + work): median {ds[len(ds) // 2] * 1000:.0f} ms, "
              f"p95 {ds[int(len(ds) * 0.95)] * 1000:.0f} ms")
    print(f"CPU (worker + readers + decoders): {(cpu_end - cpu_at_warmup) / steady:.2f} cores in steady state "
          f"({(cpu_end - cpu0) / (end - started):.2f} average incl. start-up)")
    print(f"Peak memory: {peak_rss / 2**20:.0f} MB with {peak_readers} ffmpeg readers; "
          f"{rss0 / 2**20:.0f} MB before any room (worker + decoders), "
          f"~{(peak_rss - rss0) / max(1, rooms) / 2**20:.0f} MB more per room")
    print(f"Watcher failures: {s.watcher_failures}, codes found: {s.codes_found}, cues sent: {s.cues_sent}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--rooms", type=int, default=50)
    parser.add_argument("--seconds", type=float, default=90)
    parser.add_argument("--decoders", type=int, default=os.cpu_count() or 1)
    args = parser.parse_args()
    asyncio.run(main(args.rooms, args.seconds, args.decoders))
