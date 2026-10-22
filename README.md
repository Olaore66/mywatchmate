# MyWatchMate backend

Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway. Concept: [watchwithme-idea-conceptualization.pdf](watchwithme-idea-conceptualization.pdf).

## Layout

| Package | Area | Tables |
|---|---|---|
| `auth` | Sign-up, sign-in, tokens (users + guests) | `refresh_tokens` |
| `identity` | Accounts and profile (`/me`) | `users`, `device_tokens` |
| `watch` | Spaces and synced viewing | `spaces`, `participants`, `content_items`, `schedules`, `watch_sessions` |
| `track` | Accountability | `attendance`, `streaks`, `notes`, `reminders` |
| `connect` | Chat | `chat_messages` (monthly partitions) |
| `give` | QR cue → in-room payment | `payment_links`, `space_blocked_links`, `cue_detections`, `gifts` |
| `config` | Security, JWT, Swagger, WebSocket (STOMP at `/ws`), scheduling | |
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
| Unit: one class at a time, dependencies mocked with Mockito | `*Test` | No | `.\mvnw.cmd test "-Dtest=*Test"` |
| Integration: the whole app through HTTP/JPA | `*Tests` | Yes (uses your `.env` settings; data is rolled back) | `.\mvnw.cmd test "-Dtest=*Tests"` |
| Everything | | Yes | `.\mvnw.cmd test` |

Unit tests are the fast feedback loop while coding; run everything before pushing.

On Windows use `mvnw.cmd`; the bash `./mvnw` script fails to install Maven from Git Bash.

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

Re-using an old refresh token signs that login out everywhere (`REFRESH_TOKEN_REUSED`) — it means
the token was probably copied. A guest becomes a user with `POST /auth/upgrade` (guest token),
keeping their history. WebSocket clients send `Authorization: Bearer <accessToken>` on the STOMP CONNECT frame.

## Not built yet

- Spaces, content, scheduling and live-sync endpoints (Phases 2–4)
- Redis fan-out for multiple instances (single-instance in-memory broker for now)
- AI cue worker and Payaza SDK integration
