# Real-time events (WebSocket)

REST endpoints are documented in Swagger (`/swagger-ui.html`). Swagger can't describe
WebSocket traffic, so live events are documented here.

## Connecting

- **Endpoint:** `ws://<host>/ws` (STOMP over WebSocket; e.g. `@stomp/stompjs` on web)
- **Sign in:** send the access token as a header on the STOMP **CONNECT** frame:

  ```js
  const client = new Client({
    brokerURL: "ws://localhost:8081/ws",
    connectHeaders: { Authorization: `Bearer ${accessToken}` },
  });
  ```

  Missing, expired or invalid token → the server answers with an ERROR frame and closes.
  When the access token expires, refresh it over REST and reconnect.

## Subscribing to a room

```js
client.subscribe(`/topic/spaces/${spaceId}/events`, (frame) => {
  const event = JSON.parse(frame.body);
  // event.type, event.spaceId, event.at, event.data
});
```

Only **current members** of the room (users or guests) can subscribe. Anyone else gets an ERROR
frame.

**Removed or left:** the server closes that person's connections to the room straight away, with
WebSocket close code **4403** ("No longer in this room"). Leave the room screen and don't
reconnect to it; if you need the socket for other rooms, reconnect and subscribe to those only.

## Event envelope

```json
{
  "type": "NOW_PLAYING_CHANGED",
  "spaceId": "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e",
  "at": "2026-10-22T06:00:03.512Z",
  "data": { }
}
```

Events are sent only after the change is saved, so it's safe to act on them immediately.
The person who made a change also receives its event; use it, or ignore it if you already
updated the screen from the REST response.

## Event types

| `type` | When | `data` |
|---|---|---|
| `MEMBER_JOINED` | Someone joined (user via link, or guest) | Participant |
| `MEMBER_LEFT` | Someone left | Participant |
| `MEMBER_REMOVED` | Owner/admin removed someone | Participant (if it's you: you've been removed, leave the room screen) |
| `ROLE_CHANGED` | Someone became admin or member | Participant (with new `role`) |
| `OWNERSHIP_TRANSFERRED` | Owner handed over the room | `{ "previousOwner": Participant, "newOwner": Participant }` |
| `SPACE_UPDATED` | Title, description or settings changed | SpaceInfo |
| `SPACE_ARCHIVED` | Owner archived the room | SpaceInfo (leave the room screen) |
| `SPACE_RESTORED` | Owner restored it | SpaceInfo |
| `CONTENT_ADDED` | A video was added to the room's list | Content |
| `CONTENT_REMOVED` | A video was removed | `{ "contentId": "…" }` |
| `NOW_PLAYING_CHANGED` | The room switched video, or stopped | Content, or `null` when stopped |
| `SCHEDULE_UPDATED` | A schedule was created, changed or stopped (`active: false`) | Schedule; refetch `GET /spaces/{id}/sessions` |
| `SESSION_SCHEDULED` | A one-off session was added | Session |
| `SESSION_STARTED` | A session went live (on its own at its time, or by a host / "watch now") | Session (`status: LIVE`) |
| `SESSION_ENDED` | A live session finished (video over, 3-hour limit, or a host ended it) | Session |
| `SESSION_CANCELLED` | A host cancelled a scheduled session | Session |
| `PLAYBACK_CHANGED` | Someone pressed play/pause/seek, or a new video started (from 0) | Playback |
| `PRESENCE_CHANGED` | Someone opened or closed the room (first tab in / last tab out) | Presence |
| `ATTENDANCE_FINALISED` | A session ended and attendance was decided | `{ "sessionId", "attendedCount", "expectedCount" }` ("8 of 10 attended"); refetch `GET /me/streak` |
| `NOTE_ADDED` | A shared note was written, or a private one was shared | Note |
| `NOTE_UPDATED` | A shared note was edited | Note |
| `NOTE_DELETED` | A shared note was deleted, or made private | `{ "noteId": "…" }` |
| `CHAT_MESSAGE` | Someone sent a chat message (you too: same `id` as your POST response) | ChatMessage |
| `CHAT_DELETED` | A message was deleted (by its sender or a host) | `{ "messageId": "…" }`: show "message deleted" |
| `MEMBER_MUTED` | A host muted someone in the chat | `{ "participant": Participant, "mutedUntil": "…" }` (null: until unmuted) |
| `MEMBER_UNMUTED` | A host unmuted them | `{ "participantId": "…" }` |
| `TYPING` | Someone is typing (at most every 2 s each; hide after ~4 s without another) | `{ "participantId", "displayName", "emoji": null }` |
| `REACTION` | A floating reaction | `{ "participantId", "displayName", "emoji": "🙏" }` |

### Shapes

**Participant**
```json
{ "id": "7a9b…", "displayName": "Bisi", "role": "MEMBER", "guest": true, "joinedAt": "2026-10-22T05:58:00Z" }
```

**SpaceInfo**
```json
{ "id": "1b2c…", "title": "6am Prayer Room", "description": null, "audienceType": "DIASPORA",
  "allowGuests": true, "membersCanAddContent": false, "membersCanControlPlayback": false, "archived": false }
```

**Content** (same as `ContentResponse` in Swagger)
```json
{ "id": "5c6d…", "source": "YOUTUBE", "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "externalId": "dQw4w9WgXcQ", "title": "Morning Prayer, Day 12",
  "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
  "durationSeconds": null, "live": false,
  "addedBy": { "id": "7a9b…", "displayName": "Ada" }, "createdAt": "2026-10-22T06:00:03Z" }
```

**Session** (same as `SessionResponse` in Swagger; in events, times are in UTC)
```json
{ "id": "9e8d…", "spaceId": "1b2c…", "spaceTitle": "6am Prayer Room", "scheduleId": "4f5a…",
  "title": "Morning Devotion", "status": "LIVE",
  "scheduledAt": "2026-11-02T05:00:00Z", "scheduledAtLocal": "2026-11-02T05:00:00Z", "localTimezone": "UTC",
  "startedAt": "2026-11-02T05:00:04Z", "endedAt": null, "content": { …Content… } }
```
Show times in the viewer's own timezone by converting `scheduledAt` on the device (or use
`scheduledAtLocal` from the REST endpoints, which is already in the signed-in user's timezone).

**Playback** (same as `PlaybackResponse` in Swagger; see *Watching together* below)
```json
{ "contentId": "5c6d…", "status": "PLAYING", "positionMs": 754000, "serverTimeMs": 1793577600000,
  "durationMs": 600000, "live": false, "version": 42,
  "updatedBy": { "id": "7a9b…", "displayName": "Tunde" } }
```
`status` is `STOPPED` (nothing to play, `contentId` null), `PLAYING` or `PAUSED`. A finished video
is `PAUSED` with `positionMs` = `durationMs`. `durationMs` is null when unknown (YouTube) or live.
`updatedBy` is null when the change wasn't a button press (new video, session start).

**Presence** (same as `PresenceResponse` in Swagger)
```json
{ "onlineCount": 2,
  "online": [ { "participantId": "7a9b…", "displayName": "Ada", "guest": false },
              { "participantId": "8b0c…", "displayName": "Bisi", "guest": true } ] }
```
Each person once, in the order they came online, however many tabs or devices they use.

**Note** (same as `NoteResponse` in Swagger). It's yours when `author.id` equals your `myParticipantId`.
```json
{ "id": "3c4d…", "spaceId": "1b2c…", "sessionId": "9e8d…",
  "author": { "id": "7a9b…", "displayName": "Bisi" }, "body": "Amen!", "visibility": "SHARED",
  "positionSeconds": 750, "createdAt": "2026-11-02T05:12:30Z", "updatedAt": "2026-11-02T05:12:30Z" }
```
Private notes never produce events: only their author ever sees them.

**ChatMessage** (same as `ChatMessageResponse` in Swagger). It's yours when `sender.id` equals your `myParticipantId`.
```json
{ "id": "6d7e…", "spaceId": "1b2c…", "sessionId": null,
  "sender": { "id": "7a9b…", "displayName": "Bisi" }, "senderGuest": true,
  "body": "Amen 🙏", "deleted": false, "deletedByHost": false, "createdAt": "2026-11-02T05:12:30.123456Z" }
```
`sessionId` is set when the message was sent during a live session. Deleted messages keep their place
with `deleted: true` and `body: null`.

**Schedule** (same as `ScheduleResponse` in Swagger)
```json
{ "id": "4f5a…", "spaceId": "1b2c…", "title": "Morning Devotion",
  "days": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"], "time": "06:00", "timezone": "Africa/Lagos",
  "startsOn": "2026-11-02", "endsOn": null, "active": true, "content": null,
  "nextSessionAt": "2026-11-02T05:00:00Z" }
```

## Playing videos

On `NOW_PLAYING_CHANGED` (and on opening a room, from `nowPlaying` in `GET /spaces/{id}`):

| `source` | Player |
|---|---|
| `YOUTUBE` | YouTube IFrame Player API with `externalId` |
| `VIMEO` | Vimeo Player SDK with `url` |
| `URL` | HTML5 `<video>` (`.m3u8` needs hls.js outside Safari) |

**Autoplay:** browsers block autoplay with sound until the person has interacted with the page.
Show a "Tap to watch together" button when someone enters a room; after that tap, later videos
can start automatically. Alternative: start muted with an "Unmute" button.

## Chat

Messages are **sent over REST** (`POST /spaces/{id}/chat`, see Swagger: errors like `CHAT_MUTED` and
`CHAT_RATE_LIMITED` come back properly) and **arrive over the WebSocket** as `CHAT_MESSAGE`.
History: `GET /spaces/{id}/chat` gives the newest 50, oldest first; pass `olderCursor` back as `before`
to load the page above when the person scrolls up.

**Typing and reactions are sent over the WebSocket** (they're throwaway, never stored):

```js
// while the person types (the server passes on at most one every 2 seconds)
client.publish({ destination: `/app/spaces/${spaceId}/typing` });

// a floating reaction: one of 🙏 ❤️ 🔥 😂 👏 😮 (anything else is ignored)
client.publish({
  destination: `/app/spaces/${spaceId}/reaction`,
  body: JSON.stringify({ emoji: "🙏" }),
  headers: { "content-type": "application/json" },
});
```

Only these two destinations accept SEND, and only for rooms you're in; anything else gets an ERROR frame.
Typing and reactions from muted people are dropped.

**Unread badges:** `unreadCount` is in `GET /spaces` (room list) and `GET /spaces/{id}`. Call
`POST /spaces/{id}/chat/read` when the chat is on screen, and again as messages arrive while it is.
**Muted:** `chatMuted` / `chatMutedUntil` in `GET /spaces/{id}`; disable the input while muted.

## Watching together (live sync)

The server keeps **one playback state per room**; nobody's player is in charge. Every player
lines itself up with that state, so late joiners, reconnects and slow phones all land on the
same moment.

### 1. Learn the server's clock (once per room visit)

Phone clocks are often seconds off. Call `GET /api/v1/time` (public) 3–5 times and keep the
fastest round trip:

```js
async function clockOffset() {
  let best = { rtt: Infinity, offset: 0 };
  for (let i = 0; i < 5; i++) {
    const t0 = Date.now();
    const { serverTimeMs } = await (await fetch(`${API}/api/v1/time`)).json();
    const t1 = Date.now();
    if (t1 - t0 < best.rtt) best = { rtt: t1 - t0, offset: serverTimeMs - (t0 + t1) / 2 };
  }
  return best.offset;
}
const serverNow = () => Date.now() + offset;
```

### 2. Where should the player be right now?

```js
function expectedPositionMs(p) {
  if (p.status !== "PLAYING") return p.positionMs;
  const ms = p.positionMs + (serverNow() - p.serverTimeMs);
  return p.durationMs == null ? ms : Math.min(ms, p.durationMs);
}
```

### 3. Apply a state

Sources of state: `playback` in `GET /spaces/{id}` (on opening the room), `GET /spaces/{id}/playback`
(after reconnecting), and every `PLAYBACK_CHANGED` event.

```js
let current = null;
function apply(p) {
  if (current && p.version < current.version) return;  // an older message arriving late
  current = p;
  if (p.status === "STOPPED") return player.stop();
  if (p.live) return p.status === "PLAYING" ? player.playAtLiveEdge() : player.pause();
  player.seekTo(expectedPositionMs(p));
  p.status === "PLAYING" ? player.play() : player.pause();
}
```

If `contentId` differs from the video loaded, load `nowPlaying` first (a `NOW_PLAYING_CHANGED`
always arrives just before the matching `PLAYBACK_CHANGED`).

### 4. Pressing the buttons

Only when `canControlPlayback` is true in `GET /spaces/{id}` (owner and admins always; members when
the room's `membersCanControlPlayback` is on, which is the default for COUPLE and FRIENDS rooms;
guests never). Hide or disable the controls otherwise; a viewer pausing their own player is fine
but won't affect anyone else, and the drift check will pull them back while the room plays.

```http
POST /api/v1/spaces/{id}/playback
{ "action": "PLAY" }                       // or "PAUSE"
{ "action": "SEEK", "positionMs": 754000 } // keeps playing if it was playing
```

The response is the new state; apply it at once (it has the new `version`), and ignore your own
`PLAYBACK_CHANGED` echo since it has the same `version`. Errors: `FORBIDDEN_ROLE`,
`NOTHING_PLAYING`, `LIVE_CANNOT_SEEK`, `POSITION_OUT_OF_RANGE`, `PLAYBACK_RATE_LIMITED`
(more than 5 presses a second; for a seek bar, send only when the person lets go).

### 5. Stay in sync (drift)

Every 3–5 seconds while `PLAYING`, compare the player's time with `expectedPositionMs(current)`.
More than **1.5 s** apart → `seekTo` it. Don't correct smaller gaps; it makes playback stutter.

Never pause the room because one person is buffering. They catch up when their video loads.

### 6. Who's watching (and attendance)

`onlineCount` is in `GET /spaces/{id}`, and `GET /spaces/{id}/presence` lists the people. After that,
`PRESENCE_CHANGED` carries the whole list each time someone opens or closes the room. Someone whose
phone loses signal drops off within about half a minute, once their heartbeats stop (stompjs sends them every 10 s by default; keep that on).

Being online is also what counts as **attendance**: while a session is LIVE, everyone subscribed to
the room builds up watch time. Nobody taps "I'm here". When it ends, each person counts if they watched
at least half of it, at most 20 minutes, and the room gets `ATTENDANCE_FINALISED`. So keep the
subscription open while someone is on the room screen, and close it when they leave the screen.
