-- Phase 2: rooms, membership and "now playing" content.

-- Removed people can't rejoin from the same invite link (guests re-enter as new guests,
-- so rotating the link is what keeps them out).
ALTER TABLE participants
    ADD COLUMN removed_at  TIMESTAMPTZ,
    ADD COLUMN removed_by  UUID REFERENCES participants (id) ON DELETE SET NULL;

-- "Who is in this room right now" is the most frequent membership query
CREATE INDEX ix_participants_active ON participants (space_id) WHERE left_at IS NULL;

ALTER TABLE content_items
    ADD COLUMN thumbnail_url  TEXT,
    ADD COLUMN is_live        BOOLEAN NOT NULL DEFAULT FALSE;

-- The video everyone in the room is watching; pasting a link sets it by default
ALTER TABLE spaces
    ADD COLUMN current_content_id        UUID REFERENCES content_items (id) ON DELETE SET NULL,
    ADD COLUMN members_can_add_content   BOOLEAN NOT NULL DEFAULT FALSE;
