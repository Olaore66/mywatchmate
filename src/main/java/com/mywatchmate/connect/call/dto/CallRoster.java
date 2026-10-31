package com.mywatchmate.connect.call.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Who's in the room's call (also sent as CALL_UPDATED)")
public record CallRoster(
    @Schema(example = "3") int count,
    List<CallParticipant> participants
) {
    public static CallRoster of(List<CallParticipant> participants) {
        return new CallRoster(participants.size(), participants);
    }
}
