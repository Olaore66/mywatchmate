package com.mywatchmate.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Who has the room open right now (each person once, however many tabs or devices)")
public record PresenceResponse(
    @Schema(example = "3") int onlineCount,
    @Schema(description = "In the order they came online") List<OnlineViewer> online
) {
    public static PresenceResponse of(List<OnlineViewer> online) {
        return new PresenceResponse(online.size(), online);
    }
}
