-- Review of reported payment links. Three scam reports now HOLD a link (still blocked everywhere) until a
-- member of staff confirms it as a scam (BLOCKED) or clears it (ALLOWED). Every decision is kept for good.

-- Staff can review links. Granted only by deploy/grant-staff.sh, never through the API.
ALTER TABLE users ADD COLUMN staff_role VARCHAR(16) CHECK (staff_role IN ('MODERATOR', 'ADMIN'));

-- HELD: waiting for review (blocked meanwhile). BLOCKED: confirmed scam. ALLOWED: never held, or cleared.
ALTER TABLE payment_links DROP CONSTRAINT payment_links_status_check;
ALTER TABLE payment_links ADD CONSTRAINT payment_links_status_check CHECK (status IN ('ALLOWED', 'HELD', 'BLOCKED'));
-- When the status last changed: for a HELD link, how long it has waited
ALTER TABLE payment_links ADD COLUMN status_changed_at TIMESTAMPTZ;
-- Optimistic locking: a viewer's write can't silently undo a reviewer's decision
ALTER TABLE payment_links ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Links blocked by reports so far were never reviewed: they join the queue
UPDATE payment_links SET status = 'HELD' WHERE status = 'BLOCKED' AND blocked_reason = 'Reported by viewers; under review';
UPDATE payment_links SET status_changed_at = last_seen_at;
ALTER TABLE payment_links ALTER COLUMN status_changed_at SET NOT NULL;
ALTER TABLE payment_links ALTER COLUMN status_changed_at SET DEFAULT now();
CREATE INDEX ix_payment_links_review ON payment_links (status, status_changed_at, id) WHERE status <> 'ALLOWED';

-- The audit trail: one row per decision, never changed or removed
CREATE TABLE link_reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_link_id  UUID         NOT NULL REFERENCES payment_links (id),
    reviewer_id      UUID         REFERENCES users (id) ON DELETE SET NULL,
    reviewer_name    VARCHAR(100) NOT NULL,           -- kept even if the account goes
    decision         VARCHAR(16)  NOT NULL CHECK (decision IN ('CONFIRM_SCAM', 'CLEAR')),
    from_status      VARCHAR(16)  NOT NULL,
    to_status        VARCHAR(16)  NOT NULL,
    note             VARCHAR(500) NOT NULL,
    reports_reviewed INTEGER      NOT NULL,
    decided_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_link_reviews_link ON link_reviews (payment_link_id, decided_at DESC);

CREATE FUNCTION link_reviews_append_only() RETURNS trigger AS $$
BEGIN
    -- The only change allowed: forgetting a reviewer whose account was deleted
    IF TG_OP = 'UPDATE' AND NEW.reviewer_id IS NULL
       AND (NEW.id, NEW.payment_link_id, NEW.reviewer_name, NEW.decision, NEW.from_status, NEW.to_status,
            NEW.note, NEW.reports_reviewed, NEW.decided_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.payment_link_id, OLD.reviewer_name, OLD.decision, OLD.from_status, OLD.to_status,
            OLD.note, OLD.reports_reviewed, OLD.decided_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'link_reviews is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER link_reviews_append_only BEFORE UPDATE OR DELETE ON link_reviews
    FOR EACH ROW EXECUTE FUNCTION link_reviews_append_only();

-- A report counts towards holding the link until a review deals with it
ALTER TABLE payment_link_reports ADD COLUMN review_id UUID REFERENCES link_reviews (id);
