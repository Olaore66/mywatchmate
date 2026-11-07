# MyWatchMate backend

Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway. Concept: [watchwithme-idea-conceptualization.pdf](watchwithme-idea-conceptualization.pdf).

## Layout

| Package | Area | Tables |
|---|---|---|
| `auth` | Sign-up, sign-in, tokens (users + guests) | `refresh_tokens` |
| `identity` | Accounts and profile (`/me`) | `users`, `device_tokens` |
| `watch` | Rooms, members, invites, videos | `spaces`, `participants`, `content_items`, `schedules`, `watch_sessions` |
| `sessions` | Schedules, sessions, the jobs that start/end them | (uses `schedules`, `watch_sessions`) |
| `live` | Shared play/pause/seek, server clock, who's online, closing removed members' connections | `playback_states` |
| `notify` | Reminder emails, device registration | (uses `reminders`, `device_tokens`) |
| `invite` | Email invitations (queued, sent by a job, marked joined) | `space_invitations` |
| `track` | Attendance (from presence), streaks, notes | `attendance`, `streaks`, `notes` (+ `reminders`, used by `notify`) |
| `connect` | Room chat (kept forever), mutes, typing and reactions; video calls through LiveKit (`connect.call`) | `chat_messages` (monthly partitions) |
| `give` | Give prompts (host or AI cue) → in-room Payaza checkout, gifts, blocks, scam reports | `payment_links`, `space_blocked_links`, `cue_detections`, `gifts`, `payment_link_reports` |
| `moderation` | Staff review of reported payment links: queue, evidence, confirm/clear, audit trail | `link_reviews` (+ `users.staff_role`) |
| `cluster` | Running one copy of the app or many: live events, presence and rate limits (in memory, or shared through Redis), single-copy job lock | `job_locks` |
| `config` | Security, JWT, Swagger, WebSocket (STOMP at `/ws`), clock, background jobs switch | |
| `common` | Error format (problem+json), shared helpers | |

The schema lives in `src/main/resources/db/migration`. Flyway owns it; Hibernate only validates
that entities match (`ddl-auto: validate`). Change the schema with a new `V<next>__...sql` file,
never by editing one that has already run.

## Run locally

1. Create a database and user (once), in `psql` as the `postgres` superuser:

   ```sql
   CREATE USER mywatchmate WITH PASSWORD 'choose-a-password';
   CREATE DATABASE mywatchmate OWNER mywatchmate;
   ```

2. Copy `.env.example` to `.env` in the project root and fill in `DB_PASSWORD`, `JWT_SECRET`
   (32+ random characters; the app refuses to start without it) and `DB_URL` if your Postgres
   isn't on port 5432. `.env` is gitignored; never put passwords in `application.yml`.
   Make sure Java 21 is used: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"`.

3. Run:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   Health check: http://localhost:8081/actuator/health (change the port with `SERVER_PORT` in `.env`)

## Tests

| Kind | Naming | Needs a database? | Run |
|---|---|---|---|
| Unit: one class at a time with Mockito; controllers via `@WebMvcTest` (real security rules, mocked services) | `*Test` | No | `.\mvnw.cmd test "-Dtest=*Test"` |
| Integration: the whole app through HTTP/JPA | `*Tests` | Yes (uses your `.env` settings; data is rolled back) | `.\mvnw.cmd test "-Dtest=*Tests"` |
| Everything | | Yes | `.\mvnw.cmd test` |

Unit tests are the fast feedback loop while coding; run everything before pushing.
Every test run writes a coverage report to `target/site/jacoco/index.html` (open it in a browser).
`RealtimeEventsTests` starts a real server and isn't rolled back (events only go out after a commit),
so it leaves a few `e2e-…@example.com` test users and rooms in the database.

On Windows use `mvnw.cmd`; the bash `./mvnw` script fails to install Maven from Git Bash.

## Staging on AWS

One EC2 server (Docker: the app + Caddy for HTTPS) and a private RDS PostgreSQL database in
`eu-west-2`, about $35–40/month. Secrets (database password, JWT secret) are generated straight into
AWS Parameter Store; nobody types or sees them. Settings live in `deploy/staging.conf`.

Needs: AWS CLI signed in (`aws configure`), Docker Desktop running, Git Bash.

| Task | Command (Git Bash, from the project root) |
|---|---|
| Create staging (first time, ~15 min; safe to re-run) | `deploy/setup-staging.sh` |
| Ship the current commit (~3 min) | `deploy/release.sh` |
| Delete everything (keeps a final DB snapshot) | `deploy/teardown-staging.sh` |
| Make someone staff (they sign up first), or undo it | `deploy/grant-staff.sh email MODERATOR\|ADMIN\|none` |

Run the tests before releasing. The server is reached through AWS Session Manager (no SSH keys);
logs: `aws ssm start-session --target <instance-id>` then `sudo docker logs -f app`.

## API docs (for the frontend)

- **Swagger UI:** http://localhost:8081/swagger-ui.html — try any endpoint; use **Authorize** with an access token
- **OpenAPI JSON:** http://localhost:8081/v3/api-docs — import into Postman or generate a typed client

Every endpoint is under `/api/v1`. Errors are `application/problem+json` with a stable `code`
(e.g. `EMAIL_TAKEN`, `INVALID_CREDENTIALS`, `UNAUTHENTICATED`) for the frontend to switch on.

### Login flow

| Who | Get tokens | Keep | When the access token expires (401) |
|---|---|---|---|
| User | `POST /auth/register` or `/auth/login` | `accessToken` (15 min) in memory, `refreshToken` (30 days) in secure storage | `POST /auth/refresh` → **replace both tokens**; refresh tokens are single use |
| Guest | `POST /auth/guest` with an invite token | `accessToken`, `deviceToken` (long-lived) | `POST /auth/guest/refresh` with the device token |

Forgotten password: `POST /auth/password/forgot` emails a one-time link to `<FRONTEND_URL>/reset-password?token=…`,
then `POST /auth/password/reset`. Sign-up and guest upgrade email a link to `<FRONTEND_URL>/verify-email?token=…`,
then `POST /auth/email/verify` (`emailVerified` is on `/me`; unconfirmed accounts still work).

Re-using an old refresh token signs that login out everywhere (`REFRESH_TOKEN_REUSED`) — it means
the token was probably copied. A guest becomes a user with `POST /auth/upgrade` (guest token),
keeping their history. WebSocket clients send `Authorization: Bearer <accessToken>` on the STOMP CONNECT frame.

### Rooms in one minute

1. `POST /spaces` (optionally with `videoUrl`) → the creator is OWNER; the video plays at once.
2. `GET /spaces/{id}/invite` → share `inviteUrl` (`<FRONTEND_URL>/join/<token>`), or let us email it:
   `POST /spaces/{id}/invites/email` with `{ "emails": [...], "customMessage": "..." }` (owner/admins; call it right
   after `POST /spaces` on Create Space). `GET` the same path lists who was invited and who joined.
3. Join screen: `GET /invites/{token}` (no sign-in) → then `POST /invites/{token}/join` (user)
   or `POST /auth/guest` (guest).
4. In the room: `GET /spaces/{id}` → start the `nowPlaying` player; subscribe to
   `/topic/spaces/{id}/events` for live updates.
5. Paste a link: `POST /content/preview` for a preview card → `POST /spaces/{id}/content` to play it for everyone.

6. Sessions: a host creates a schedule (`POST /spaces/{id}/schedules`, e.g. weekdays 06:00 Africa/Lagos);
   sessions for the next 14 days appear in `GET /spaces/{id}/sessions` and `GET /me/upcoming`. They start
   and end on their own; members get `SESSION_STARTED` / `SESSION_ENDED`. "Watch now" starts one immediately.
7. Watching together: `GET /time` for the clock offset, line the player up with `playback` from `GET /spaces/{id}`,
   send presses to `POST /spaces/{id}/playback`, and follow `PLAYBACK_CHANGED`. Recipe in
   [docs/realtime-events.md](docs/realtime-events.md#watching-together-live-sync).
8. Tracking: attendance is automatic while a session is live (being in the room is enough). After it ends:
   `GET /spaces/{id}/sessions/{sid}/attendance`, `GET /me/streak` (also `myStreak` in `GET /spaces/{id}`),
   `GET /me/attendance`; hosts get `GET /spaces/{id}/attendance`. Notes: `/spaces/{id}/notes` (private or shared).
9. Chat: `GET/POST /spaces/{id}/chat` (send over REST, receive `CHAT_MESSAGE` live), typing and reactions over
   the WebSocket, `unreadCount` badges, hosts mute with `POST /spaces/{id}/participants/{pid}/mute`.
10. Video calls: `POST /spaces/{id}/call/join` → `{ url, token }` for the LiveKit SDK; `GET /spaces/{id}/call` and
    `CALL_UPDATED` for who's on. Settings: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` (empty: calls off).
    LiveKit's webhook goes to `POST /api/v1/webhooks/livekit` (signature-checked).
11. Giving: a host puts up the room's Payaza link (`POST /spaces/{id}/give/prompts`) during a live session, or the
    AI does (Phase 8, via `POST /internal/cues`); viewers open the checkout in-room and report the result.
    Settings: `PAYAZA_HOSTS` (accepted Payaza link domains), `INTERNAL_CUE_TOKEN` (the AI worker's token;
    empty turns `/internal` off).
12. Scam review (staff only): 3 reports from different people **hold** a link (not shown anywhere) and email staff.
    A reviewer (`GET /admin/links`, `GET /admin/links/{id}`) confirms it (`BLOCKED`) or clears it (`ALLOWED`) with
    `POST /admin/links/{id}/decision`; `POST /admin/links/block` blocks a link before anyone reports it. Decisions
    are kept for good in `link_reviews` (the database refuses edits). The staff role is checked in the database
    on every call and granted only with `deploy/grant-staff.sh`; `staffRole` on `/me` tells the app to show the screen.

### Limits on sign-in and sign-up

Bots go for the public endpoints, so each has a per-address limit (settings under `mywatchmate.abuse`):
sign-in 30/min, sign-up and guest upgrade 5/hour and 20/day, guest join 20/hour, token refresh 60/min,
forgot password 5/hour, emailed links 20/hour, WebSocket handshake 30/min. Wrong passwords are also counted
per account: 5 from one address in 15 minutes block that account **from that address** (a success there clears
it), and 20 from anywhere in an hour slow it down everywhere. Nothing locks an owner out for good: blocks clear
as old failures age. Over a limit: **429 `TOO_MANY_ATTEMPTS`** with a `Retry-After` header (seconds), readable
by the browser (CORS exposes it); Swagger lists the 429 on every guarded endpoint. Emails are only kept hashed.

The client's address comes from Tomcat's proxy handling (`forward-headers-strategy: native`): it reads
`X-Forwarded-For` from the right and only trusts private-network proxies, so a client can't fake its address.
With `CLUSTER_MODE=REDIS` the counts are shared by every copy. `ABUSE_LIMITS_ENABLED=false` turns it all off.

### One server now, more later

Staging runs **one copy of the app and one database**, with nothing else to run. The parts that would break
with a second copy are behind a switch, `CLUSTER_MODE`:

| | `LOCAL` (default) | `REDIS` (several copies behind a load balancer) |
|---|---|---|
| Room events over the WebSocket | this copy's in-memory broker | sent through Redis pub/sub; every copy delivers to its own clients |
| Who's online | this copy's connections | each copy's list in Redis, refreshed every 10 s; a crashed copy drops off after 30 s |
| Rate limits (chat, playback, emails) | in memory | one shared sliding window per person in Redis (allowed if Redis is down) |
| Removing someone from a room | closes their connections here | every copy closes theirs |
| Background jobs | one row lock per job in Postgres (`job_locks`) | the same: each turn runs on one copy |

The frontend sees no difference: same endpoints, WebSocket topics and message JSON, byte for byte (tested).
To go to several copies: run Redis (e.g. ElastiCache), set `CLUSTER_MODE=REDIS`, `REDIS_HOST`, `REDIS_PORT`
(and `REDIS_PASSWORD` / `REDIS_SSL=true` if needed) and `REDIS_HEALTH_ENABLED=true`, then add copies behind a
load balancer that supports WebSockets. No sticky sessions needed. The Redis tests run in Docker
(Testcontainers) and are skipped when Docker isn't running.

### Background jobs

| Job | Every | Does |
|---|---|---|
| `SessionGeneratorJob` | hour | Keeps 14 days of sessions planned for every active schedule |
| `SessionLifecycleJob` | 30 s | Starts sessions at their time; ends them when the video is over (or after 3 h) |
| `ReminderDispatchJob` | 30 s | Sends due reminder emails (printed to the log when `MAIL_HOST` isn't set) |
| `InviteDispatchJob` | 30 s | Sends queued invitation emails; retries 3 times, then FAILED |
| `AttendanceJob` | 30 s | Adds watch time for everyone with a live session's room open |
| `GiftExpiryJob` | 10 min | Gifts opened over an hour ago with no result become UNKNOWN |
| `ChatPartitionJob` | day | Creates next months' chat tables |

Their timers live in `config/ScheduledJobs`; each turn runs on one copy only (`JobLock`, a row in `job_locks`),
and the jobs also lock the rows they work on. Tests switch them off
(`mywatchmate.jobs.enabled=false`) and call them directly with a controlled clock.

Permissions are in the **Spaces** section of Swagger; live events are in
[docs/realtime-events.md](docs/realtime-events.md), including the browser **autoplay** rule.

## Not built yet

- Push notifications (device tokens are already collected; sending needs Firebase/APNs credentials)
- AI cue worker (Phase 8: spots giving QR codes in the stream and calls `POST /internal/cues`)
- Payaza's exact link domains (placeholder `payaza.africa` in `PAYAZA_HOSTS` until Payaza confirms)
