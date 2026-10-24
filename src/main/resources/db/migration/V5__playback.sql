-- Phase 4: live sync. One shared playback state per room; presence lives in memory.

-- Can members (not just owner/admins) play, pause and seek for everyone?
ALTER TABLE spaces ADD COLUMN members_can_control_playback BOOLEAN NOT NULL DEFAULT FALSE;
-- Couples and friends are equals; diaspora/work/other rooms are led by their hosts
UPDATE spaces SET members_can_control_playback = TRUE WHERE audience_type IN ('COUPLE', 'FRIENDS');

-- "As of position_at the video was at position_ms, and it was PLAYING/PAUSED."
-- Clients work out where they should be now from this and the server clock.
CREATE TABLE playback_states (
    space_id    UUID PRIMARY KEY REFERENCES spaces (id),
    content_id  UUID REFERENCES content_items (id) ON DELETE SET NULL,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('STOPPED', 'PLAYING', 'PAUSED')),
    position_ms BIGINT      NOT NULL DEFAULT 0 CHECK (position_ms >= 0),
    position_at TIMESTAMPTZ NOT NULL,
    -- Goes up by one on every change; clients ignore anything older than what they have
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_by  UUID REFERENCES participants (id)
);

-- Existing rooms start paused at the beginning of whatever they were showing
INSERT INTO playback_states (space_id, content_id, status, position_ms, position_at, version)
SELECT id, current_content_id, CASE WHEN current_content_id IS NULL THEN 'STOPPED' ELSE 'PAUSED' END, 0, now(), 0
FROM spaces;
