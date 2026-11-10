// Keeps one video player lined up with the room's shared playback state.
// Framework-free: wrap your player in the small adapter below, and call tick() about 4 times a second.
// Full explanation: "Watching together" in /docs/realtime-events.md
//
// The rules it follows:
//   1. The server's state is the truth; a player only ever follows it.
//   2. Only a person's intent becomes a command. A player reporting "playing" after a buffer is not a press.
//   3. Never correct a player that's busy loading. Small gaps are closed by playing slightly faster or slower;
//      a jump is the last resort, at most once per cool-down, and it aims ahead to allow for how long loading takes.
//
// player adapter: { getTimeMs(), getDurationMs(), isBuffering(), isPaused(), hasEnded(),
//                   seekTo(ms), play(), pause(), setRate(rate) }
// serverNow():    Date.now() plus the offset from GET /time
// send(command):  POST /spaces/{id}/playback; returns (a promise of) the new state, or nothing
// canControl():   canControlPlayback from GET /spaces/{id}

export const DEFAULTS = {
  nudgeAboveMs: 300,       // closer than this counts as in sync
  seekAboveMs: 3000,       // further than this: jump; between the two: play a little faster or slower
  applySeekAboveMs: 1500,  // a new state only moves the player if it's at least this far off
  cooldownMs: 8000,        // the least time between corrective jumps
  ignoreEventsMs: 1500,    // player events this soon after we moved it are our own doing
  pauseSettleMs: 400,      // a pause only counts if buffering doesn't follow (dragging the bar pauses briefly)
  scrubJumpMs: 2500,       // the player's time jumping by this much, unprompted, is someone dragging the bar
  maxLeadMs: 4000,
};

export function createWatchSync({ player, serverNow, send, canControl = () => false, options = {} }) {
  const o = { ...DEFAULTS, ...options };
  let room = null;
  let ignoreUntil = 0;
  let cooldownUntil = 0;
  let pendingPauseAt = null;
  let lead = 0;              // how far ahead to aim a jump, learned from how far behind the last one landed
  let landing = false;       // a corrective jump is still loading
  let last = null;           // the player's time at the previous tick
  let rate = 1;

  const setRate = (next) => { if (next !== rate) { rate = next; player.setRate(next); } };
  const ignoreOwnEvents = () => { ignoreUntil = serverNow() + o.ignoreEventsMs; };

  function expectedMs() {
    if (room.status !== "PLAYING") return room.positionMs;
    const ms = room.positionMs + (serverNow() - room.serverTimeMs);
    const end = room.durationMs ?? player.getDurationMs();
    return end == null ? ms : Math.min(ms, end);
  }

  function moveTo(ms) { ignoreOwnEvents(); last = null; player.seekTo(ms); }

  function press(command) {
    ignoreOwnEvents();
    Promise.resolve(send(command)).then((state) => { if (state) applyState(state); }).catch(() => {});
  }

  /** Every state from the server: GET /playback, POST responses, PLAYBACK_CHANGED. */
  function applyState(state) {
    if (room && state.version < room.version) return;     // an older message arriving late
    const changed = !room || state.version > room.version;
    room = state;
    if (!changed) return;                                 // same state, newer clock reading: nothing to do
    pendingPauseAt = null;
    if (state.status === "STOPPED") { ignoreOwnEvents(); player.pause(); return; }
    if (!state.live && Math.abs(player.getTimeMs() - expectedMs()) > o.applySeekAboveMs) moveTo(expectedMs());
    ignoreOwnEvents();
    setRate(1);
    if (state.status === "PLAYING") player.play(); else player.pause();
  }

  /** Call from the player's own events, with "PLAYING", "PAUSED" or "BUFFERING". */
  function onPlayerEvent(kind) {
    if (kind === "BUFFERING") { pendingPauseAt = null; return; }
    if (!room || !canControl() || serverNow() < ignoreUntil) return;
    if (kind === "PAUSED" && room.status === "PLAYING") pendingPauseAt = serverNow() + o.pauseSettleMs;
    if (kind === "PLAYING") {
      pendingPauseAt = null;
      if (room.status === "PAUSED") press({ action: "PLAY" });   // PLAYING while the room plays is just a buffer ending
    }
  }

  function noticeDraggingTheBar(now, time) {
    if (last && canControl() && now >= ignoreUntil) {
      const moved = time - last.time;
      const shouldHave = player.isPaused() || player.isBuffering() ? 0 : (now - last.at) * rate;
      if (Math.abs(moved - shouldHave) > o.scrubJumpMs) {
        last = { time, at: now };
        pendingPauseAt = null;
        press({ action: "SEEK", positionMs: Math.round(time) });
        return true;
      }
    }
    last = { time, at: now };
    return false;
  }

  /** Call about 4 times a second. */
  function tick() {
    if (!room) return;
    const now = serverNow();
    const time = player.getTimeMs();
    if (pendingPauseAt != null && now >= pendingPauseAt) {
      pendingPauseAt = null;
      if (player.isPaused() && room.status === "PLAYING") press({ action: "PAUSE" });
      return;
    }
    if (noticeDraggingTheBar(now, time)) return;
    if (room.status !== "PLAYING") {
      setRate(1);
      if (!canControl() && room.status === "PAUSED" && !player.isPaused() && !player.isBuffering()) {
        ignoreOwnEvents();
        player.pause();
      }
      return;
    }
    if (room.live) { if (player.isPaused()) { ignoreOwnEvents(); player.play(); } return; }
    const expected = expectedMs();
    const end = room.durationMs ?? player.getDurationMs();
    if (player.hasEnded() || (end != null && expected >= end - 500)) { setRate(1); return; }
    if (player.isBuffering()) return;                     // never correct a player that's loading
    if (player.isPaused()) {
      if (pendingPauseAt == null) { ignoreOwnEvents(); player.play(); }
      return;
    }
    if (landing) {                                        // the jump finished: learn how far short it fell
      landing = false;
      lead = Math.min(o.maxLeadMs, Math.max(0, lead + (expected - time)));
    }
    const drift = time - expected;
    if (Math.abs(drift) >= o.seekAboveMs) {
      if (now >= cooldownUntil) {
        cooldownUntil = now + o.cooldownMs;
        landing = true;
        setRate(1);
        moveTo(expected + lead);
        return;
      }
      setRate(drift < 0 ? 1.25 : 0.75);
    } else if (Math.abs(drift) > o.nudgeAboveMs) {
      setRate(drift < 0 ? 1.25 : 0.75);
    } else {
      setRate(1);
    }
  }

  return { applyState, onPlayerEvent, tick, expectedMs: () => (room ? expectedMs() : null) };
}
