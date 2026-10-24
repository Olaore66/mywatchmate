package com.mywatchmate.connect.dto;

import com.mywatchmate.connect.ChatMessage;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.PersonRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A chat message. Yours when sender.id is your myParticipantId.")
public record ChatMessageResponse(
    UUID id,
    UUID spaceId,
    @Schema(description = "The session that was live when it was sent", nullable = true) UUID sessionId,
    @Schema(description = "Participant id and name") PersonRef sender,
    boolean senderGuest,
    @Schema(description = "Null once deleted: show \"message deleted\"", example = "Amen 🙏", nullable = true) String body,
    boolean deleted,
    @Schema(description = "Deleted by a host rather than the sender") boolean deletedByHost,
    Instant createdAt
) {
    public static ChatMessageResponse from(ChatMessage m) {
        Participant sender = m.getParticipant();
        boolean byHost = m.isDeleted() && m.getDeletedBy() != null && !m.getDeletedBy().getId().equals(sender.getId());
        return new ChatMessageResponse(m.getId(), m.getSpace().getId(),
            m.getSession() == null ? null : m.getSession().getId(),
            new PersonRef(sender.getId(), sender.displayName()), sender.isGuest(),
            m.isDeleted() ? null : m.getBody(), m.isDeleted(), byHost, m.getCreatedAt());
    }
}
