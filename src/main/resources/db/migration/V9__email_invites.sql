-- Email invitations: a host types addresses; we record each one, and a background job emails them the
-- room's invite link. Also an outbox: nothing is lost if the mail server is down or the app restarts.
CREATE TABLE space_invitations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id        UUID         NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    -- Trimmed and lower-cased
    email           VARCHAR(254) NOT NULL,
    invited_by      UUID         REFERENCES participants (id) ON DELETE SET NULL,
    message         VARCHAR(500),
    status          VARCHAR(16)  NOT NULL
                    CHECK (status IN ('PENDING', 'SENT', 'JOINED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    -- When the job should (next) try to send it
    next_attempt_at TIMESTAMPTZ,
    -- Last time it was queued (first send or a resend); counts towards the room's daily limit
    last_queued_at  TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    joined_at       TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL
);

-- One live invitation per address per room
CREATE UNIQUE INDEX ux_space_invitations_live ON space_invitations (space_id, email) WHERE status IN ('PENDING', 'SENT');
-- What the job picks up
CREATE INDEX ix_space_invitations_due ON space_invitations (next_attempt_at) WHERE status = 'PENDING';
-- A room's list, newest first, and its daily count
CREATE INDEX ix_space_invitations_space ON space_invitations (space_id, created_at DESC);
CREATE INDEX ix_space_invitations_queued ON space_invitations (space_id, last_queued_at);
