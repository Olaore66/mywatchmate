package com.mywatchmate.invite.dto;

import com.mywatchmate.invite.SpaceInvitation;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.PersonRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An email invitation, for the room's invited list")
public record InvitationResponse(
    UUID id,
    @Schema(example = "friend@example.com") String email,
    @Schema(description = "PENDING (being sent), SENT, JOINED, FAILED (couldn't be delivered), CANCELLED, EXPIRED")
    SpaceInvitation.Status status,
    @Schema(nullable = true) PersonRef invitedBy,
    Instant createdAt,
    @Schema(nullable = true) Instant sentAt,
    @Schema(nullable = true) Instant joinedAt,
    Instant expiresAt,
    @Schema(description = "Why the last send failed", nullable = true) String lastError
) {
    public static InvitationResponse from(SpaceInvitation i, Instant now) {
        Participant by = i.getInvitedBy();
        return new InvitationResponse(i.getId(), i.getEmail(), i.statusAt(now),
            by == null ? null : new PersonRef(by.getId(), by.displayName()), i.getCreatedAt(), i.getSentAt(),
            i.getJoinedAt(), i.getExpiresAt(), i.getLastError());
    }
}
