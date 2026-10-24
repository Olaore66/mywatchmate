-- Phase 6a: chat belongs to the room (chatting between sessions too); a message sent while a
-- session is live is tagged with it.

ALTER TABLE chat_messages ADD COLUMN space_id UUID REFERENCES spaces (id) ON DELETE CASCADE;
UPDATE chat_messages m SET space_id = s.space_id FROM watch_sessions s WHERE s.id = m.session_id;
ALTER TABLE chat_messages ALTER COLUMN space_id SET NOT NULL;
ALTER TABLE chat_messages ALTER COLUMN session_id DROP NOT NULL;

-- Who deleted it (the sender, or a host moderating); the text is kept but never shown
ALTER TABLE chat_messages ADD COLUMN deleted_by UUID REFERENCES participants (id);
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_body_length CHECK (char_length(body) BETWEEN 1 AND 2000);

-- History pages walk a room's chat newest-first: (created_at, id) is the cursor
CREATE INDEX ix_chat_messages_space_time ON chat_messages (space_id, created_at DESC, id DESC);

-- A host muted this person in the room's chat until then
ALTER TABLE participants ADD COLUMN chat_muted_until TIMESTAMPTZ;
-- Everything up to here counts as read (null: since they joined)
ALTER TABLE participants ADD COLUMN chat_last_read_at TIMESTAMPTZ;
