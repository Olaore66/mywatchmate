package com.mywatchmate.give;

import com.mywatchmate.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The shared secret our own services (the AI cue worker) send as {@code X-Service-Token}. Empty turns every
 * /internal endpoint off. Compared in constant time, so it can't be guessed a character at a time.
 */
@Component
@RequiredArgsConstructor
public class ServiceToken {

    public static final String HEADER = "X-Service-Token";

    private final GiveProperties props;

    public void require(String presented) {
        String expected = props.internalToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_DISABLED", "Internal endpoints are turned off.");
        }
        if (presented == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("INVALID_SERVICE_TOKEN", "Missing or wrong service token.");
        }
    }
}
