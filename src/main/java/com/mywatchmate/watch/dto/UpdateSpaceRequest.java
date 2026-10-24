package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Space;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields to change; omit a field to leave it as it is")
public record UpdateSpaceRequest(
    @Schema(example = "Morning Devotion", nullable = true)
    @Size(min = 1, max = 150) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String title,
    @Schema(description = "Send an empty string to clear it", nullable = true) @Size(max = 2000) String description,
    @Schema(nullable = true) Space.AudienceType audienceType,
    @Schema(nullable = true) Boolean allowGuests,
    @Schema(nullable = true) Boolean membersCanAddContent,
    @Schema(nullable = true) Boolean membersCanControlPlayback
) {
}
