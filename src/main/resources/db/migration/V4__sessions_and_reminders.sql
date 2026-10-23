-- Phase 3: schedules, sessions and reminders.

ALTER TABLE schedules ADD COLUMN title VARCHAR(150);
ALTER TABLE watch_sessions ADD COLUMN title VARCHAR(150);

-- The generator can run any number of times (or on two servers) without duplicating a sitting
CREATE UNIQUE INDEX ux_watch_sessions_schedule_time
    ON watch_sessions (schedule_id, scheduled_at) WHERE schedule_id IS NOT NULL;

-- A room has at most one live session
CREATE UNIQUE INDEX ux_watch_sessions_one_live ON watch_sessions (space_id) WHERE status = 'LIVE';

-- The lifecycle job looks for sessions due to start
CREATE INDEX ix_watch_sessions_due ON watch_sessions (scheduled_at) WHERE status = 'SCHEDULED';

-- Per-room reminder preference: minutes before a session; NULL = off
ALTER TABLE participants
    ADD COLUMN reminder_minutes_before INTEGER DEFAULT 10
        CHECK (reminder_minutes_before IS NULL OR reminder_minutes_before BETWEEN 0 AND 1440);

-- Global switch for reminder emails
ALTER TABLE users ADD COLUMN email_reminders BOOLEAN NOT NULL DEFAULT TRUE;

-- Why a reminder failed, for support
ALTER TABLE reminders ADD COLUMN last_error VARCHAR(300);
