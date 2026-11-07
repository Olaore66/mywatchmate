package com.mywatchmate.abuse;

import com.mywatchmate.common.ApiException;
import java.time.Duration;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 429 TOO_MANY_ATTEMPTS, with how long to wait (sent as the Retry-After header). */
@Getter
public class TooManyAttempts extends ApiException {

    public static final String CODE = "TOO_MANY_ATTEMPTS";
    static final String MESSAGE = "Too many attempts. Please wait a little and try again.";

    private final Duration retryAfter;

    public TooManyAttempts(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, CODE, MESSAGE);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up (Retry-After takes seconds). */
    public static long seconds(Duration wait) {
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }
}
