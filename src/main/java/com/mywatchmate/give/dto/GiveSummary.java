package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "A room's giving over a period (owner and admins). Self-reported by viewers' apps, not "
    + "verified by Payaza; never says who gave what.")
public record GiveSummary(
    Instant from,
    Instant to,
    @Schema(description = "Give prompts shown", example = "4") long promptsShown,
    @Schema(description = "Times someone tapped Give", example = "23") long giveTaps,
    @Schema(example = "17") long completed,
    @Schema(description = "Failed or cancelled", example = "3") long notCompleted,
    @Schema(description = "No result reported (yet, or ever)", example = "3") long unreported,
    @Schema(description = "Completed gifts that reported an amount, per currency") List<CurrencyTotal> totals,
    @Schema(description = "Always true") boolean selfReported
) {
    @Schema(name = "GiveCurrencyTotal")
    public record CurrencyTotal(
        @Schema(example = "NGN") String currency,
        @Schema(description = "Minor units", example = "8500000") long amountMinor,
        @Schema(example = "14") long gifts
    ) {
    }
}
