# MyWatchMate backend

Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway. Concept: [watchwithme-idea-conceptualization.pdf](watchwithme-idea-conceptualization.pdf).

## Layout

| Package | Area | Tables |
|---|---|---|
| `identity` | Accounts | `users`, `device_tokens` |
| `watch` | Spaces and synced viewing | `spaces`, `participants`, `content_items`, `schedules`, `watch_sessions` |
| `track` | Accountability | `attendance`, `streaks`, `notes`, `reminders` |
| `connect` | Chat | `chat_messages` (monthly partitions) |
| `give` | QR cue → in-room payment | `payment_links`, `space_blocked_links`, `cue_detections`, `gifts` |
| `config` | Security, WebSocket (STOMP at `/ws`), scheduling | |

The schema lives in `src/main/resources/db/migration`. Flyway owns it; Hibernate only validates
that entities match (`ddl-auto: validate`). Change the schema with a new `V2__...sql` file,
never by editing `V1`.

## Run locally

1. Create a database and user (once), in `psql` as the `postgres` superuser:

   ```sql
   CREATE USER mywatchmate WITH PASSWORD 'choose-a-password';
   CREATE DATABASE mywatchmate OWNER mywatchmate;
   ```

2. Copy `.env.example` to `.env` in the project root and fill in `DB_PASSWORD` (and `DB_URL`
   if your Postgres isn't on port 5432). `.env` is gitignored; never put passwords in `application.yml`.
   Make sure Java 21 is used: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"`.

3. Run:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   Health check: http://localhost:8081/actuator/health (change the port with `SERVER_PORT` in `.env`)

Tests (`.\mvnw.cmd test`) run against the same database settings; the round-trip test rolls back its data.

On Windows use `mvnw.cmd`; the bash `./mvnw` script fails to install Maven from Git Bash.

## Not built yet

- Authentication: JWT for users, device tokens for guests (everything but health and `/ws` is locked)
- REST controllers and STOMP handlers
- Redis fan-out for multiple instances (single-instance in-memory broker for now)
- AI cue worker and Payaza SDK integration
