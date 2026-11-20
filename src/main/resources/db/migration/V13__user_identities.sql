-- Sign-in with other providers (Google now). A Google account is found by its permanent id ("sub"), never by its
-- email, which can change. One Google account per user, and each Google account belongs to one user.
CREATE TABLE user_identities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider    VARCHAR(16)  NOT NULL CHECK (provider IN ('GOOGLE')),
    subject     VARCHAR(255) NOT NULL,
    email       VARCHAR(320) NOT NULL,                -- what the provider said when linked, for support
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_identities_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_identities_user UNIQUE (user_id, provider)
);
