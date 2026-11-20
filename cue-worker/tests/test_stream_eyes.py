"""The real thing: ffmpeg reading a video in which a giving QR code appears partway through."""

import shutil
import subprocess

import pytest
import qrcode
from conftest import HEIGHT, WIDTH

from worker.decoder import find_qr_codes
from worker.stream_eyes import ffmpeg_command, stream_frames

LINK = "https://business.payaza.africa/pay/nsppd"

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg isn't installed")


@pytest.fixture(scope="module")
def service_video(tmp_path_factory):
    """12 s of 1280x720 video, a keyframe every second; the QR code shows from 4 s to 9 s."""
    folder = tmp_path_factory.mktemp("video")
    qr_png = folder / "qr.png"
    qrcode.make(LINK, border=2).resize((300, 300)).save(qr_png)
    video = folder / "service.mp4"
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=0x404060:s=1280x720:r=25:d=12",
        "-i", str(qr_png),
        "-filter_complex", "[0][1]overlay=x=900:y=360:enable='between(t,4,9)'",
        "-c:v", "libx264", "-g", "25", "-pix_fmt", "yuv420p", str(video),
    ], check=True)
    return video


async def test_frames_are_small_grey_and_about_one_a_second(service_video):
    frames = [f async for f in stream_frames(str(service_video), WIDTH, HEIGHT, 1.0)]

    assert 10 <= len(frames) <= 13
    assert all(len(f) == WIDTH * HEIGHT for f in frames)


async def test_the_qr_code_is_found_only_while_it_is_on_screen(service_video):
    found = [bool(find_qr_codes(f, WIDTH, HEIGHT)) async for f in stream_frames(str(service_video), WIDTH, HEIGHT, 1.0)]

    seconds_with_code = [i for i, has in enumerate(found) if has]
    assert seconds_with_code, "the code was never seen"
    assert 3 <= seconds_with_code[0] <= 5
    assert seconds_with_code[-1] <= 10
    assert not found[0] and not found[-1]


async def test_reading_can_start_where_the_room_is(service_video):
    """The code shows from 4 s to 9 s: starting at 5 s, it's there straight away."""
    frames = [f async for f in stream_frames(str(service_video), WIDTH, HEIGHT, 1.0, start_seconds=5.0)]

    assert find_qr_codes(frames[0], WIDTH, HEIGHT) == [LINK]
    assert len(frames) <= 8


def test_recordings_are_read_in_real_time_from_a_position():
    cmd = ffmpeg_command("https://cdn/sermon.mp4", WIDTH, HEIGHT, 1.0, start_seconds=602.5, realtime=True)

    assert cmd[cmd.index("-ss") + 1] == "602.500"
    assert cmd.index("-re") < cmd.index("-i") and cmd.index("-ss") < cmd.index("-i")
    assert "-ss" not in ffmpeg_command("https://cdn/live.m3u8", WIDTH, HEIGHT, 1.0)


def test_the_command_reads_keyframes_only_and_never_audio():
    cmd = ffmpeg_command("https://cdn/live.m3u8", WIDTH, HEIGHT, 1.0)

    assert cmd[cmd.index("-skip_frame") + 1] == "nokey"
    assert "-an" in cmd
    assert "fps=1.0" in cmd[cmd.index("-vf") + 1]
