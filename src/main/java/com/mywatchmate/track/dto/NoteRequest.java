package com.mywatchmate.track.dto;

import com.mywatchmate.track.Note;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record NoteRequest(
    @Schema(example = "Psalm 23: come back to verse 4") @NotBlank @Size(max = 5000) String body,
    @Schema(description = "PRIVATE (default): only you. SHARED: the whole room, live.", nullable = true)
    Note.Visibility visibility,
    @Schema(description = "The session it's about", nullable = true) UUID sessionId,
    @Schema(description = "Where in the video, in seconds (750 = 12:30)", example = "750", nullable = true)
    @PositiveOrZero Integer positionSeconds
) {
}
