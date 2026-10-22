package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Space;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSpaceRequest(
    @Schema(example = "6am Prayer Room") @NotBlank @Size(max = 150) String title,
    @Schema(example = "Daily devotion with the Lagos family", nullable = true) @Size(max = 2000) String description,
    @Schema(example = "DIASPORA") @NotNull Space.AudienceType audienceType,
    @Schema(description = "Let people join without an account. Default true.", nullable = true) Boolean allowGuests,
    @Schema(description = "Let members (not just owner/admins) add videos. Default false.", nullable = true)
    Boolean membersCanAddContent,
    @Schema(description = "Optional first video; it starts playing for everyone straight away. "
        + "Check it first with POST /content/preview.", example = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", nullable = true)
    @Size(max = 2048) String videoUrl
) {
}
