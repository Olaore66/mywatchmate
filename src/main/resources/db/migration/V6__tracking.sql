-- Phase 5: attendance from presence, streaks, notes.

-- Last time the person was seen in the live session; "counted" is set when the session ends
ALTER TABLE attendance ADD COLUMN last_seen_at TIMESTAMPTZ;
UPDATE attendance SET last_seen_at = COALESCE(left_at, joined_at);
ALTER TABLE attendance ALTER COLUMN last_seen_at SET NOT NULL;
ALTER TABLE attendance ADD COLUMN counted BOOLEAN NOT NULL DEFAULT FALSE;

-- "My attendance" and streak rebuilding look up counted rows per participant
CREATE INDEX ix_attendance_participant_counted ON attendance (participant_id) WHERE counted;

-- Where in the video a note was taken
ALTER TABLE notes RENAME COLUMN content_position_seconds TO position_seconds;
ALTER TABLE notes ADD CONSTRAINT ck_notes_body_length CHECK (char_length(body) BETWEEN 1 AND 5000);
CREATE INDEX ix_notes_session ON notes (session_id) WHERE session_id IS NOT NULL;
