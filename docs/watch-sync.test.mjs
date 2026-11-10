// Run with: node --test docs/watch-sync.test.mjs
// A simulated room on a virtual clock: a server with the backend's rules, and YouTube-like players that land on
// the keyframe before a seek and take time to load. Proves the sync rules, not YouTube itself.
import assert from "node:assert/strict";
import test from "node:test";
import { createWatchSync } from "./watch-sync.js";

const STEP = 250;

class Server {
  constructor(now) { this.now = now; this.state = { status: "PLAYING", positionMs: 0, at: now(), version: 1 }; this.listeners = []; this.commands = []; }
  position() { const s = this.state; return s.status === "PLAYING" ? s.positionMs + this.now() - s.at : s.positionMs; }
  view() { return { status: this.state.status, positionMs: this.position(), serverTimeMs: this.now(), version: this.state.version, durationMs: null, live: false }; }
  command(c) {
    this.commands.push(c.action);
    const s = this.state;
    const same = (c.action === "PLAY" && s.status === "PLAYING") || (c.action === "PAUSE" && s.status === "PAUSED")
      || (c.action === "SEEK" && s.status === "PLAYING" && Math.abs(c.positionMs - this.position()) < 1000);
    if (same) return this.view();
    const position = c.action === "SEEK" ? c.positionMs : this.position();
    const status = c.action === "PLAY" ? "PLAYING" : c.action === "PAUSE" ? "PAUSED" : s.status;
    this.state = { status, positionMs: position, at: this.now(), version: s.version + 1 };
    const view = this.view();
    this.listeners.forEach((l) => l(view));
    return view;
  }
}

class Player {
  constructor(loadMs) { this.loadMs = loadMs; this.t = 0; this.state = "PAUSED"; this.loading = 0; this.rate = 1; this.seeks = 0; this.onEvent = () => {}; }
  getTimeMs() { return this.t; }
  getDurationMs() { return 3_600_000; }
  isBuffering() { return this.loading > 0; }
  isPaused() { return this.state === "PAUSED" && this.loading === 0; }
  hasEnded() { return false; }
  setRate(r) { this.rate = r; }
  play() { if (this.state !== "PLAYING") { this.state = "PLAYING"; this.onEvent("PLAYING"); } }
  pause() { if (this.state !== "PAUSED") { this.state = "PAUSED"; this.onEvent("PAUSED"); } }
  seekTo(ms) { this.seeks++; this.t = Math.floor(ms / 2000) * 2000; this.buffer(this.loadMs); }
  buffer(ms) { this.loading = ms; this.onEvent("BUFFERING"); }
  advance(dt) {
    if (this.loading > 0) { this.loading -= dt; if (this.loading <= 0) { this.loading = 0; this.onEvent(this.state === "PLAYING" ? "PLAYING" : "PAUSED"); } return; }
    if (this.state === "PLAYING") this.t += dt * this.rate;
  }
}

function room({ hostLoad = 800, viewerLoad = 2500 } = {}) {
  let clock = 1_000_000;
  const now = () => clock;
  const server = new Server(now);
  const join = (loadMs, control) => {
    const player = new Player(loadMs);
    const sync = createWatchSync({ player, serverNow: now, canControl: () => control, send: (c) => server.command(c) });
    player.onEvent = (kind) => sync.onPlayerEvent(kind);
    server.listeners.push((s) => sync.applyState(s));
    sync.applyState(server.view());
    return { player, sync };
  };
  const host = join(hostLoad, true);
  const viewer = join(viewerLoad, false);
  const run = (ms) => {
    for (let t = 0; t < ms; t += STEP) {
      clock += STEP;
      host.player.advance(STEP); viewer.player.advance(STEP);
      host.sync.tick(); viewer.sync.tick();
    }
  };
  return { server, host, viewer, run };
}

const gap = (who, server) => Math.abs(who.player.t - server.position());

test("a slow viewer settles instead of jumping forever", () => {
  const { server, viewer, run } = room({ viewerLoad: 2500 });
  run(60_000);
  assert.ok(viewer.player.seeks <= 3, `seeks: ${viewer.player.seeks}`);
  assert.ok(gap(viewer, server) < 1000, `gap: ${gap(viewer, server)}`);
});

test("the old rule (jump every 4 s if over 1.5 s off, even while loading) never settles on the same connection", () => {
  let clock = 0;
  const roomAt = () => 60_000 + clock;      // they join a minute in
  const player = new Player(2500);
  player.state = "PLAYING";
  for (let t = 0; t < 60_000; t += STEP) {
    clock += STEP;
    player.advance(STEP);
    if (clock % 4000 === 0 && Math.abs(player.t - roomAt()) > 1500) player.seekTo(roomAt());
  }
  assert.ok(player.seeks >= 10, `seeks: ${player.seeks}`);
});

test("the host's player buffering sends nothing", () => {
  const { server, host, run } = room();
  run(5000);
  server.commands.length = 0;
  for (let i = 0; i < 10; i++) { host.player.buffer(1800); run(6000); }
  assert.deepEqual(server.commands, []);
});

test("a real pause and play reach everyone, once each", () => {
  const { server, host, viewer, run } = room();
  run(20_000);
  server.commands.length = 0;
  host.player.pause();
  run(3000);
  assert.deepEqual(server.commands, ["PAUSE"]);
  assert.equal(viewer.player.state, "PAUSED");
  const frozen = viewer.player.t;
  run(10_000);
  assert.equal(viewer.player.t, frozen);
  host.player.play();
  run(30_000);
  assert.deepEqual(server.commands, ["PAUSE", "PLAY"]);
  assert.equal(viewer.player.state, "PLAYING");
  assert.ok(gap(viewer, server) < 1000, `gap: ${gap(viewer, server)}`);
});

test("dragging the bar is one SEEK, not a pause, and everyone follows", () => {
  const { server, host, viewer, run } = room();
  run(20_000);
  server.commands.length = 0;
  host.player.onEvent("PAUSED");            // YouTube reports this during a drag...
  host.player.t = 600_000;
  host.player.buffer(800);                  // ...then buffers at the new spot
  run(30_000);
  assert.deepEqual(server.commands, ["SEEK"]);
  assert.ok(gap(host, server) < 1000, `host gap: ${gap(host, server)}`);
  assert.ok(gap(viewer, server) < 1000, `viewer gap: ${gap(viewer, server)}`);
  assert.ok(server.position() > 600_000);
});

test("a viewer can't drift away by pausing on their own", () => {
  const { server, viewer, run } = room();
  run(10_000);
  viewer.player.pause();
  run(30_000);
  assert.equal(viewer.player.state, "PLAYING");
  assert.ok(gap(viewer, server) < 1000, `gap: ${gap(viewer, server)}`);
  assert.deepEqual(server.commands, []);
});

test("states arriving late or twice change nothing", () => {
  const { server, viewer, run } = room();
  run(20_000);
  const old = { ...server.view(), version: 0, positionMs: 5 };
  const seeks = viewer.player.seeks;
  viewer.sync.applyState(old);
  viewer.sync.applyState(server.view());
  assert.equal(viewer.player.seeks, seeks);
});
