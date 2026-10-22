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
frame. After being removed or leaving, stop using the subscription.

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

### Shapes

**Participant**
```json
{ "id": "7a9b…", "displayName": "Bisi", "role": "MEMBER", "guest": true, "joinedAt": "2026-10-22T05:58:00Z" }
```

**SpaceInfo**
```json
{ "id": "1b2c…", "title": "6am Prayer Room", "description": null, "audienceType": "DIASPORA",
  "allowGuests": true, "membersCanAddContent": false, "archived": false }
```

**Content** (same as `ContentResponse` in Swagger)
```json
{ "id": "5c6d…", "source": "YOUTUBE", "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "externalId": "dQw4w9WgXcQ", "title": "Morning Prayer, Day 12",
  "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
  "durationSeconds": null, "live": false,
  "addedBy": { "id": "7a9b…", "displayName": "Ada" }, "createdAt": "2026-10-22T06:00:03Z" }
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

Keeping everyone at the same second (play/pause/seek sync, late joiners) arrives in Phase 4 and
will be added to this document.
