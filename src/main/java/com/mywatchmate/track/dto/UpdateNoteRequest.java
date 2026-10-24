package com.mywatchmate.track.dto;

import com.mywatchmate.track.Note;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields to change; omit a field to leave it as it is")
public record UpdateNoteRequest(
    @Schema(nullable = true) @Size(min = 1, max = 5000) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
    String body,
    @Schema(nullable = true) Note.Visibility visibility,
    @Schema(nullable = true) @PositiveOrZero Integer positionSeconds
) {
}
