-- WatchWithMe: full initial schema (PostgreSQL 14+)
-- Conventions: UUID keys, timestamptz in UTC, money in minor units, enums as VARCHAR + CHECK.
-- Live playback state and presence live in Redis, not here.

-- =====================================================================
-- Identity
-- =====================================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255),                 -- null for social login
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

-- Push notification targets for reminders
CREATE TABLE device_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform        VARCHAR(16)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
    token           VARCHAR(512) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_device_tokens_user ON device_tokens (user_id);

-- =====================================================================
-- Watch
-- =====================================================================

CREATE TABLE spaces (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID NOT NULL REFERENCES users (id),
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    invite_token    VARCHAR(64)  NOT NULL UNIQUE,  -- the shareable link
    audience_type   VARCHAR(16)  NOT NULL
                    CHECK (audience_type IN ('DIASPORA', 'COUPLE', 'FRIENDS', 'WORK', 'OTHER')),
    allow_guests    BOOLEAN      NOT NULL DEFAULT TRUE,
    archived_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_spaces_owner ON spaces (owner_id);

-- Everyone in a space: registered users and guests alike.
-- A guest who signs up later keeps their history by setting user_id.
CREATE TABLE participants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id             UUID REFERENCES users (id) ON DELETE SET NULL,
    guest_display_name  VARCHAR(100),
    guest_token_hash    VARCHAR(128) UNIQUE,       -- hash of the device token stored on the guest's device
    role                VARCHAR(16) NOT NULL DEFAULT 'MEMBER'
                        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at             TIMESTAMPTZ,
    CONSTRAINT ck_participant_identity
        CHECK (user_id IS NOT NULL OR (guest_display_name IS NOT NULL AND guest_token_hash IS NOT NULL))
);
CREATE UNIQUE INDEX ux_participants_space_user ON participants (space_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_participants_user ON participants (user_id);

CREATE TABLE content_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    added_by            UUID REFERENCES participants (id) ON DELETE SET NULL,
    source              VARCHAR(16) NOT NULL
                        CHECK (source IN ('YOUTUBE', 'LIVESTREAM', 'VIMEO', 'URL')),
    external_url        TEXT NOT NULL,
    external_id         VARCHAR(255),
    title               VARCHAR(300),
    duration_seconds    INTEGER CHECK (duration_seconds >= 0),  -- null for live streams
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_content_items_space ON content_items (space_id);

-- Recurring plans, e.g. "every weekday at 06:00 Africa/Lagos"
CREATE TABLE schedules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    content_item_id     UUID REFERENCES content_items (id) ON DELETE SET NULL,
    rrule               VARCHAR(500) NOT NULL,     -- RFC 5545 recurrence rule
    timezone            VARCHAR(64)  NOT NULL,
    starts_on           DATE NOT NULL,
    ends_on             DATE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_schedules_space ON schedules (space_id);

CREATE TABLE watch_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    schedule_id         UUID REFERENCES schedules (id) ON DELETE SET NULL,
    content_item_id     UUID REFERENCES content_items (id) ON DELETE SET NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (status IN ('SCHEDULED', 'LIVE', 'ENDED', 'CANCELLED')),
    scheduled_at        TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_watch_sessions_space_time ON watch_sessions (space_id, scheduled_at);
CREATE INDEX ix_watch_sessions_live ON watch_sessions (status) WHERE status = 'LIVE';

-- =====================================================================
-- Track
-- =====================================================================

CREATE TABLE attendance (
    session_id          UUID NOT NULL REFERENCES watch_sessions (id) ON DELETE CASCADE,
    participant_id      UUID NOT NULL REFERENCES participants (id) ON DELETE CASCADE,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at             TIMESTAMPTZ,
    watched_seconds     INTEGER NOT NULL DEFAULT 0 CHECK (watched_seconds >= 0),
    PRIMARY KEY (session_id, participant_id)
);
CREATE INDEX ix_attendance_participant ON attendance (participant_id);

-- One streak per user across all spaces; recomputed from attendance. Guests have none.
CREATE TABLE streaks (
    user_id             UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    current_count       INTEGER NOT NULL DEFAULT 0 CHECK (current_count >= 0),
    longest_count       INTEGER NOT NULL DEFAULT 0 CHECK (longest_count >= 0),
    last_attended_on    DATE,                      -- in the user's own timezone
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notes (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id                    UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    session_id                  UUID REFERENCES watch_sessions (id) ON DELETE SET NULL,
    author_id                   UUID NOT NULL REFERENCES participants (id) ON DELETE CASCADE,
    content_position_seconds    INTEGER CHECK (content_position_seconds >= 0),
    body                        TEXT NOT NULL,
    visibility                  VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
                                CHECK (visibility IN ('PRIVATE', 'SHARED')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notes_space ON notes (space_id, created_at);
CREATE INDEX ix_notes_author ON notes (author_id);

-- Registered users only: guests have no channel to reach them on.
CREATE TABLE reminders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    session_id          UUID NOT NULL REFERENCES watch_sessions (id) ON DELETE CASCADE,
    channel             VARCHAR(16) NOT NULL CHECK (channel IN ('PUSH', 'EMAIL')),
    send_at             TIMESTAMPTZ NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, session_id, channel)
);
CREATE INDEX ix_reminders_due ON reminders (send_at) WHERE status = 'PENDING';

-- =====================================================================
-- Connect
-- =====================================================================

-- Kept forever, so partitioned by month. A scheduled job calls
-- create_chat_partition() ahead of each new month.
CREATE TABLE chat_messages (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES watch_sessions (id) ON DELETE CASCADE,
    participant_id      UUID NOT NULL REFERENCES participants (id) ON DELETE CASCADE,
    body                TEXT NOT NULL,
    deleted_at          TIMESTAMPTZ,               -- sender removed it; row kept
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX ix_chat_messages_session_time ON chat_messages (session_id, created_at);

CREATE FUNCTION create_chat_partition(month_start DATE) RETURNS VOID AS $$
DECLARE
    from_date DATE := date_trunc('month', month_start);
    to_date   DATE := (date_trunc('month', month_start) + INTERVAL '1 month');
    part_name TEXT := 'chat_messages_' || to_char(from_date, 'YYYY_MM');
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF chat_messages FOR VALUES FROM (%L) TO (%L)',
        part_name, from_date, to_date);
END;
$$ LANGUAGE plpgsql;

-- Seed partitions for the current month and the next three
SELECT create_chat_partition((date_trunc('month', now()) + (n || ' month')::INTERVAL)::DATE)
FROM generate_series(0, 3) AS n;

-- =====================================================================
-- Give
-- =====================================================================

-- Every payment link the AI has decoded from a QR code, checked once and cached.
-- The link owner never needs a WatchWithMe account.
CREATE TABLE payment_links (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_url      TEXT NOT NULL UNIQUE,
    provider            VARCHAR(32) NOT NULL CHECK (provider IN ('PAYAZA')),
    merchant_name       VARCHAR(200),              -- from the provider, if it exposes one
    status              VARCHAR(16) NOT NULL DEFAULT 'ALLOWED'
                        CHECK (status IN ('ALLOWED', 'BLOCKED')),
    blocked_reason      TEXT,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A room owner or admin can block a link in their own space only
CREATE TABLE space_blocked_links (
    space_id            UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    payment_link_id     UUID NOT NULL REFERENCES payment_links (id) ON DELETE CASCADE,
    blocked_by          UUID REFERENCES participants (id) ON DELETE SET NULL,
    reason              TEXT,
    blocked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, payment_link_id)
);

CREATE TABLE cue_detections (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id                  UUID NOT NULL REFERENCES watch_sessions (id) ON DELETE CASCADE,
    payment_link_id             UUID REFERENCES payment_links (id),   -- null if it wasn't a valid payment link
    cue_type                    VARCHAR(16) NOT NULL DEFAULT 'QR'
                                CHECK (cue_type IN ('QR', 'LINK', 'SPOKEN')),
    raw_value                   TEXT NOT NULL,
    confidence                  NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    content_position_seconds    INTEGER CHECK (content_position_seconds >= 0),
    status                      VARCHAR(16) NOT NULL
                                CHECK (status IN ('SHOWN', 'REJECTED', 'BLOCKED')),
    reject_reason               VARCHAR(100),
    detected_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cue_detections_session ON cue_detections (session_id, detected_at);

-- One row per tap of the Give button. The result comes back from the provider's
-- SDK in the viewer's app, so 'UNKNOWN' is a legitimate final state.
CREATE TABLE gifts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    detection_id        UUID REFERENCES cue_detections (id) ON DELETE SET NULL,
    session_id          UUID NOT NULL REFERENCES watch_sessions (id),
    participant_id      UUID NOT NULL REFERENCES participants (id),
    payment_link_id     UUID NOT NULL REFERENCES payment_links (id),
    is_anonymous        BOOLEAN NOT NULL DEFAULT FALSE,
    amount_minor        BIGINT CHECK (amount_minor > 0),   -- null until the SDK reports it
    currency            CHAR(3),
    provider_txn_ref    VARCHAR(128) UNIQUE,
    status              VARCHAR(16) NOT NULL DEFAULT 'OPENED'
                        CHECK (status IN ('OPENED', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    opened_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT ck_gift_amount_currency CHECK ((amount_minor IS NULL) = (currency IS NULL))
);
CREATE INDEX ix_gifts_session ON gifts (session_id, opened_at);
CREATE INDEX ix_gifts_participant ON gifts (participant_id);
