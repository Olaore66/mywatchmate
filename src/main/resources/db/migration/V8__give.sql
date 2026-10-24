-- Phase 7: giving. A payment link (from the AI in Phase 8, or pasted by a host) becomes a Give prompt in
-- the live session; viewers open Payaza's checkout in the room and report what it said.

-- Who put the prompt up: the AI worker, or a host by hand
ALTER TABLE cue_detections ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'AI' CHECK (source IN ('AI', 'HOST'));
ALTER TABLE cue_detections ADD COLUMN shown_by UUID REFERENCES participants (id);
-- A shown prompt stays up until it expires, a host closes it, or another replaces it
ALTER TABLE cue_detections ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE cue_detections ADD COLUMN closed_at TIMESTAMPTZ;
CREATE INDEX ix_cue_detections_shown ON cue_detections (session_id, payment_link_id, detected_at) WHERE status = 'SHOWN';

-- Results come from the viewer's app (Payaza confirms to the link's owner, not to us)
ALTER TABLE gifts ADD COLUMN reported_at TIMESTAMPTZ;
CREATE INDEX ix_gifts_opened ON gifts (opened_at) WHERE status = 'OPENED';

-- "This looks like a scam": one report per person (user, or guest participant) per link
ALTER TABLE payment_links ADD COLUMN report_count INTEGER NOT NULL DEFAULT 0;
CREATE TABLE payment_link_reports (
    payment_link_id UUID         NOT NULL REFERENCES payment_links (id) ON DELETE CASCADE,
    reporter_key    VARCHAR(64)  NOT NULL,
    detection_id    UUID         REFERENCES cue_detections (id) ON DELETE SET NULL,
    reason          VARCHAR(300),
    reported_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_link_id, reporter_key)
);
