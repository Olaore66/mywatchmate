package com.mywatchmate.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "What happened to one address")
public record EmailInviteResult(
    @Schema(description = "The address as we understood it (trimmed, lower-case)", example = "friend@example.com")
    String email,
    Outcome status,
    @Schema(description = "Set when QUEUED or ALREADY_INVITED", nullable = true) UUID invitationId
) {
    public enum Outcome {
        /** Will be emailed within a minute */
        QUEUED,
        /** Already in the room */
        ALREADY_MEMBER,
        /** Already invited and waiting; use resend */
        ALREADY_INVITED,
        /** Not an email address */
        INVALID_EMAIL,
        /** The room's daily limit is reached; try tomorrow */
        RATE_LIMITED
    }
}
