-- Verified churches: a church registers its Payaza PUBLIC key and payment links with MyWatchMate once, staff verify
-- it's really that church, and from then on its links are paid inside ANY room, with a "verified" badge.

CREATE TABLE churches (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name               VARCHAR(200) NOT NULL,
    website            VARCHAR(500),
    official_email     VARCHAR(320),
    official_phone     VARCHAR(40),
    payaza_public_key  VARCHAR(200) NOT NULL,
    mode               VARCHAR(8)   NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    currency_code      CHAR(3)      NOT NULL DEFAULT 'NGN',
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    applied_by         UUID         REFERENCES users (id) ON DELETE SET NULL,
    verified_by        UUID         REFERENCES users (id) ON DELETE SET NULL,
    verified_at        TIMESTAMPTZ,
    status_changed_at  TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_churches_status ON churches (status, status_changed_at, id);
CREATE INDEX ix_churches_applied_by ON churches (applied_by);

-- A payment link belongs to at most one church. A rejected application gives its links back.
CREATE TABLE church_links (
    church_id        UUID NOT NULL REFERENCES churches (id) ON DELETE CASCADE,
    payment_link_id  UUID NOT NULL REFERENCES payment_links (id),
    PRIMARY KEY (church_id, payment_link_id),
    CONSTRAINT uq_church_links_link UNIQUE (payment_link_id)
);

-- A verified church asking for a new key or new links: used only once staff approve it
CREATE TABLE church_changes (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    church_id          UUID         NOT NULL REFERENCES churches (id) ON DELETE CASCADE,
    payaza_public_key  VARCHAR(200),
    mode               VARCHAR(8)   CHECK (mode IN ('TEST', 'LIVE')),
    payment_links      TEXT[],
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by       UUID         REFERENCES users (id) ON DELETE SET NULL,
    requested_at       TIMESTAMPTZ  NOT NULL,
    decided_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_church_changes_pending ON church_changes (church_id) WHERE status = 'PENDING';

-- Every staff decision about a church, kept for good
CREATE TABLE church_reviews (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    church_id      UUID          NOT NULL REFERENCES churches (id),
    reviewer_id    UUID          REFERENCES users (id) ON DELETE SET NULL,
    reviewer_name  VARCHAR(100)  NOT NULL,
    decision       VARCHAR(24)   NOT NULL
                   CHECK (decision IN ('APPROVE', 'REJECT', 'SUSPEND', 'REINSTATE', 'APPROVE_CHANGE', 'REJECT_CHANGE')),
    from_status    VARCHAR(16)   NOT NULL,
    to_status      VARCHAR(16)   NOT NULL,
    note           VARCHAR(1000) NOT NULL,
    decided_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_church_reviews_church ON church_reviews (church_id, decided_at DESC);

CREATE FUNCTION church_reviews_append_only() RETURNS trigger AS $$
BEGIN
    -- The only change allowed: forgetting a reviewer whose account was deleted
    IF TG_OP = 'UPDATE' AND NEW.reviewer_id IS NULL
       AND (NEW.id, NEW.church_id, NEW.reviewer_name, NEW.decision, NEW.from_status, NEW.to_status, NEW.note, NEW.decided_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.church_id, OLD.reviewer_name, OLD.decision, OLD.from_status, OLD.to_status, OLD.note, OLD.decided_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'church_reviews is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER church_reviews_append_only BEFORE UPDATE OR DELETE ON church_reviews
    FOR EACH ROW EXECUTE FUNCTION church_reviews_append_only();

-- Which verified church a gift went to (null: a room's own registration, or a new-tab gift)
ALTER TABLE gifts ADD COLUMN church_id UUID REFERENCES churches (id);
CREATE INDEX ix_gifts_church ON gifts (church_id) WHERE church_id IS NOT NULL;
