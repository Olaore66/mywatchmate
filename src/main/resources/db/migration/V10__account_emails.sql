-- Email verification and password reset. Existing accounts start unverified and can ask for a new link.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- One-time links we emailed. Only a hash is stored, so a database leak gives nobody a working link.
CREATE TABLE account_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(16) NOT NULL CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET')),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);

-- Finding a user's unused links, to retire them when a new one is issued
CREATE INDEX ix_account_tokens_open ON account_tokens (user_id, purpose) WHERE used_at IS NULL;
