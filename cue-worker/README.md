# MyWatchMate AI cue worker

Watches every live room's video about once a second and, when a giving QR code appears, tells the backend
(`POST /api/v1/internal/cues`). The backend does the rest: checks it's a real Payaza link that isn't blocked, shows
the room a Give card, and (for verified churches) opens Payaza's checkout inside the room.

## How it works

- **Supervisor:** every 15 s asks the backend which sessions are live (`GET /api/v1/internal/live-sessions`) and keeps
  exactly one watcher per live room: new rooms get one, ended rooms lose theirs, a failing one is retried with a
  growing wait (5 s up to 5 min).
- **Stream eyes:** ffmpeg reads the room's stream, decoding **keyframes only**, about once a second, shrunk to
  960x540 grey. Each room costs a small slice of a CPU.
- **Decoder:** zxing-cpp finds QR codes, in a pool of processes (one per CPU) so image work never holds anything up.
  While a frame is being read, newer ones replace each other: the latest frame always wins, so there's no backlog.
- **Shared readers:** rooms watching the same live stream (say, 50 rooms on one church's morning service) share a
  single reader and decode each frame once; every room still gets, dedupes and reports its own cue. A reader starts
  with the first room on its stream and stops with the last. Recordings and calls are never shared: each room is at
  its own place in the video or has its own call. `/health` shows `rooms` and `readers` side by side.
- **Dedupe:** the same code in the same session is reported once per 10 minutes (the backend checks again).
- **Shards:** `WORKER_SHARD=1/3` makes this worker take one third of the rooms (by session id), so more workers
  simply means more capacity, with no room watched twice.

**What it watches**, depending on the room:

| Room plays | How the worker sees it |
|---|---|
| A live stream (direct `.m3u8` link) | Reads the stream. Live is decided from the playlist itself: no `#EXT-X-ENDLIST` line |
| A recording or `.mp4` (direct link) | Follows the room: reads from where the room is, in real time; a seek restarts it at the new place, a pause stops it |
| YouTube or Vimeo | Never downloaded. When the host shares their screen in the room's call, the worker joins as a **hidden, view-only** viewer (nobody sees it; it can't speak, show video or send anything) and reads **only the screen share** |

`/health` lists rooms skipped and why (`PAUSED`, `YOUTUBE_NO_SCREEN_SHARE`, ...).

## Run it on your laptop (against staging)

Needs Python 3.12+ and ffmpeg on the PATH. In PowerShell, from this folder:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:INTERNAL_CUE_TOKEN = aws ssm get-parameter --region eu-west-2 --name /mywatchmate/staging/INTERNAL_CUE_TOKEN --with-decryption --query Parameter.Value --output text
python -m worker.main
```

Then open http://localhost:8090/health to see rooms watched, frames, codes found and cues sent. Stop with Ctrl+C.

## Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `INTERNAL_CUE_TOKEN` | (required) | The backend's service token (from AWS Parameter Store; never commit it) |
| `MWM_API_BASE` | `https://51-24-72-13.sslip.io` | The backend |
| `MAX_ROOMS` | `40` | Rooms this worker watches at once |
| `WORKER_SHARD` | `0/1` | Which share of the rooms: `0/2` and `1/2` split them between two workers |
| `POLL_SECONDS` | `15` | How often to ask which sessions are live |
| `FRAMES_PER_SECOND` | `1` | Frames looked at per second, per room |
| `DEDUPE_MINUTES` | `10` | The same code in the same session is reported once per this long |
| `DECODERS` | one per CPU | Decoding processes |
| `HEALTH_PORT` | `8090` | Where `/health` answers |
| `WATCH_CALLS` | `true` | `false` stops it joining calls to read screen shares |
| `LOG_LEVEL` | `INFO` | `DEBUG` for more |

## Tests

```powershell
pip install -r requirements-dev.txt
python -m pytest
```

They use real QR images, a fake backend, and (if ffmpeg is installed) a real video in which a QR code appears.

## Docker

```powershell
docker build -t mywatchmate-cue-worker .
docker run --rm -e INTERNAL_CUE_TOKEN=$env:INTERNAL_CUE_TOKEN -p 8090:8090 mywatchmate-cue-worker
```

## Staging

The worker runs on the staging server as a second container (`cue-worker`) next to the app, on the same docker
network, talking to it directly at `http://app:8081`. It is given **only** its token (not the database password or
any other app secret) and is capped at 1 CPU and 512 MB, so it can never slow the app down. From the project root, in
Git Bash:

```bash
deploy/release-worker.sh
```

It builds this folder's image, pushes it to the existing ECR repository as `worker-<commit>` and `worker-latest`, and
starts it on the server through SSM. To see it working there:

```bash
aws ssm start-session --target <instance-id>    # then: sudo docker logs --tail 50 cue-worker
```

## Load test

`loadtest/load_test.py` runs the real supervisor, ffmpeg readers and decoders against N live rooms (a pretend backend
records the cues) and reports frames per second, dropped frames, time to each cue, CPU and memory:

```powershell
.\.venv\Scripts\python -m pip install psutil
.\.venv\Scripts\python loadtest\load_test.py --rooms 50 --seconds 90
```
