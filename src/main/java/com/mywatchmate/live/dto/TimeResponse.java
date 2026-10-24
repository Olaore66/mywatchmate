package com.mywatchmate.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "The server's clock, for working out how far off this device's clock is")
public record TimeResponse(
    @Schema(description = "Epoch milliseconds", example = "1793577600000") long serverTimeMs,
    @Schema(example = "2026-11-02T00:00:00Z") Instant serverTime
) {
    public static TimeResponse at(Instant now) {
        return new TimeResponse(now.toEpochMilli(), now);
    }
}
