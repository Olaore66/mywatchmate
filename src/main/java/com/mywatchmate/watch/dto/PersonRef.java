package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A person as shown in the room")
public record PersonRef(
    @Schema(example = "7a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d") UUID id,
    @Schema(example = "Ada Okafor") String displayName
) {
}
