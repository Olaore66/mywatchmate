package com.mywatchmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields to change; omit a field to leave it as it is")
public record UpdateProfileRequest(
    @Schema(example = "Ada O.", nullable = true)
    @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
    String displayName,

    @Schema(description = "IANA timezone", example = "Africa/Lagos", nullable = true)
    @Size(max = 64)
    String timezone
) {
}
