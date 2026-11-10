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
- **Reconnecting:** at most 30 connection attempts a minute per network address; past that the handshake
  gets HTTP 429 with `Retry-After`. Reconnect with a growing delay (stompjs `reconnectDelay`), not in a tight loop.

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

`at` is the server time the change happened. The topic is **server-only**: clients can't publish to it
(the only frames clients may SEND are typing and reactions, see *Chat*).

There is **no `PLAYBACK_STATE` event**: playback changes arrive as `PLAYBACK_CHANGED` (see *Watching together*).

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
| `GIVE_PROMPT` | A Give button appeared (AI spotted a QR code, or a host put the link up); replaces any showing | Prompt |
| `GIVE_PROMPT_CLOSED` | Take the Give card down (host closed it, link blocked or reported) | `{ "promptId": "…" }` |
| `CALL_UPDATED` | Someone joined or left the room's video call, or turned camera/screen share on or off | `{ "count": 2, "participants": [ { "participantId", "displayName", "joinedAt", "camera", "microphone", "screenSharing" } ] }` |
| `GIFT_MADE` | Someone completed a gift | `{ "displayName": "Bisi", "anonymous": false }` (displayName null when anonymous); never an amount |

### Shapes

**Participant**
```json
{ "id": "7a9b…", "displayName": "Bisi", "role": "MEMBER", "guest": true, "joinedAt": "2026-10-22T05:58:00Z",
  "chatMuted": true, "chatMutedUntil": "2026-10-22T06:30:00Z" }
```
`chatMuted`: a host muted them in the chat and it hasn't run out. `chatMutedUntil` is when it runs out (null when
not muted, or muted until a host unmutes them). `GET /spaces/{id}/participants` has the same fields, so hosts
still see who's muted after a refresh.

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

**Prompt** (same as `PromptResponse` in Swagger)
```json
{ "id": "8f9a…", "sessionId": "9e8d…", "url": "https://pay.payaza.africa/give/stmarks", "provider": "PAYAZA",
  "merchantName": null, "source": "HOST", "shownAt": "2026-11-02T05:40:00Z", "expiresAt": "2026-11-02T05:45:00Z",
  "notice": "You're paying the link's owner through Payaza. MyWatchMate never holds your money." }
```

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

## Video calls (LiveKit)

Camera and microphone go through **LiveKit**, not our WebSocket. `POST /api/v1/spaces/{id}/call/join` returns
`{ url, token }`; connect with the LiveKit SDK (`livekit-client`, or `@livekit/components-react` for ready-made
video tiles) and publish the camera/mic. Each person's LiveKit identity is their `participantId` in the space,
so tiles can be matched to members. `CALL_UPDATED` (here) says who's in the call, for "3 on camera" in the room
UI; inside the call itself, the SDK's own events are faster. Someone removed from the room is disconnected
from its call at once.

## Giving

During a live session a **Give card** can appear (`GIVE_PROMPT`). Someone opening the room late gets it from
`GET /spaces/{id}/give/prompts/current` (204 when nothing is up). Show it until `expiresAt` or
`GIVE_PROMPT_CLOSED`, always with its `notice`.

1. Tap **Give** → `POST /spaces/{id}/give/prompts/{promptId}/open` with `{ "anonymous": true|false }`.
2. Open the returned `checkoutUrl` **without leaving the room**:
   - **Mobile:** an in-app browser over the video (iOS `SFSafariViewController`, Android Custom Tabs),
     so the stream keeps playing underneath.
   - **Web:** a popup window (`window.open`, sized like a checkout); keep the player running.
3. When the checkout closes (or Payaza's page says paid/failed), report it **once**:
   `POST /gifts/{giftId}/result` with `{ "status": "COMPLETED" | "FAILED" | "CANCELLED", "amountMinor", "currency",
   "providerTxnRef" }` (amount in kobo/cents, e.g. 500000 = NGN 5,000). If you can't tell, don't report: after
   an hour it becomes UNKNOWN.
4. The room gets `GIFT_MADE`: show a gentle "Bisi gave 🙏" (or "Someone gave 🙏"). There's never an amount.

Results are **self-reported**: Payaza confirms payments to the link's owner, not to MyWatchMate. Offer a
"Report this link" option on the card (`POST …/prompts/{promptId}/report`); three reports block a link everywhere.

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

### 3. Use the sync client

**[/docs/watch-sync.js](/docs/watch-sync.js)** does steps 3–5 for you: copy it into the app, wrap your player in the
small adapter described at its top, and connect it up:

```js
const sync = createWatchSync({
  player,                                   // the adapter
  serverNow,                                // from step 1
  canControl: () => room.canControlPlayback,
  send: (command) => api.post(`/spaces/${id}/playback`, command),
});

// every state, from anywhere
api.get(`/spaces/${id}/playback`).then(sync.applyState);
socket.on("PLAYBACK_CHANGED", (event) => sync.applyState(event.data));

// from the player's own events
youtube.onStateChange = ({ data }) => {
  if (data === 1) sync.onPlayerEvent("PLAYING");
  if (data === 2) sync.onPlayerEvent("PAUSED");
  if (data === 3) sync.onPlayerEvent("BUFFERING");
};

setInterval(sync.tick, 250);
```

If `contentId` differs from the video loaded, load `nowPlaying` first (a `NOW_PLAYING_CHANGED` always arrives just
before the matching `PLAYBACK_CHANGED`).

**Connecting and reconnecting:** every time you subscribe to the room, the server immediately sends *that one
connection* the room's current state, as a `PLAYBACK_CHANGED`. So a phone that lost signal catches up by itself. As a
backstop, also call `GET /spaces/{id}/playback` every 30 s and pass the result to `sync.applyState`; states with the
same `version` do nothing.

**Video length:** `durationMs` is null for YouTube until someone reports it. When the player is ready, if
`durationMs` is null and you can control playback, call `POST /spaces/{id}/playback/duration` with
`{ "contentId": "…", "durationMs": 212000 }`. Without it, the server can't tell when the video finishes.

### 4. The rules, if you write your own

**A press is a person's intent, never a player's report.** A YouTube player reports "playing" every time it recovers
from buffering, and reports "paused" for a moment while someone drags the bar. Sending those to the server makes
every viewer re-seek, which makes them buffer, and the room keeps jumping over the same few seconds.

- Send `PLAY` only if the room is paused. A `PLAYING` report while the room plays is a buffer ending: ignore it.
- Wait about 400 ms before treating a `PAUSED` report as a press; if `BUFFERING` follows, it was a drag.
- For a drag, send one `SEEK` when the player's time jumps by more than 2.5 s without you moving it.
- Ignore the player's reports for about 1.5 s after you moved it yourself.

Only when `canControlPlayback` is true in `GET /spaces/{id}` (owner and admins always; members when
the room's `membersCanControlPlayback` is on, which is the default for COUPLE and FRIENDS rooms;
guests never). Hide or disable the controls otherwise.

```http
POST /api/v1/spaces/{id}/playback
{ "action": "PLAY" }                       // or "PAUSE"
{ "action": "SEEK", "positionMs": 754000 } // keeps playing if it was playing
```

The response is the new state; apply it at once, and ignore your own `PLAYBACK_CHANGED` echo since it has the same
`version`. PLAY while playing, PAUSE while paused, and SEEK to within a second of where a playing room already is
change nothing: you get the current state back with the same `version`, and no event. Errors: `FORBIDDEN_ROLE`,
`NOTHING_PLAYING`, `LIVE_CANNOT_SEEK`, `POSITION_OUT_OF_RANGE`, `PLAYBACK_RATE_LIMITED`
(more than 5 presses a second).

### 5. Staying in sync (drift)

**Never correct a player that's loading**, and never pause the room because one person is buffering. While the
player is actually playing, compare it with where it should be:

| How far off | What to do |
|---|---|
| Under 0.3 s | Nothing; play at normal speed |
| 0.3 s to 3 s | Play at 1.25× if behind, 0.75× if ahead, until it's back. No jump, so nothing visibly skips |
| Over 3 s | Jump, at most once every 8 s, aiming ahead by however far short the last jump landed |

A jump lands on the keyframe before the target and then takes time to load, so it always lands short. Jumping again
because of that is what makes a video loop on a slow connection.

### 6. Who's watching (and attendance)

**Closing the tab is not leaving.** `POST /spaces/{id}/leave` takes the person *out of the room*: a user has to
rejoin from the invite link, and a guest's session ends for good. Never call it when a tab closes. Just let the
WebSocket close: the server takes them off the online list at once on disconnect, or within about half a minute if
the connection drops without closing (missed heartbeats). No polling is needed; `PRESENCE_CHANGED` tells everyone.

`onlineCount` is in `GET /spaces/{id}`, and `GET /spaces/{id}/presence` lists the people. After that,
`PRESENCE_CHANGED` carries the whole list each time someone opens or closes the room. Someone whose
phone loses signal drops off within about half a minute, once their heartbeats stop (stompjs sends them every 10 s by default; keep that on).

Being online is also what counts as **attendance**: while a session is LIVE, everyone subscribed to
the room builds up watch time. Nobody taps "I'm here". When it ends, each person counts if they watched
at least half of it, at most 20 minutes, and the room gets `ATTENDANCE_FINALISED`. So keep the
subscription open while someone is on the room screen, and close it when they leave the screen.
