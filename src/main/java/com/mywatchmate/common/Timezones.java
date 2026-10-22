package com.mywatchmate.common;

import java.time.DateTimeException;
import java.time.ZoneId;

public final class Timezones {

    private Timezones() {
    }

    /** Validates an IANA timezone name ("Europe/London"); blank means UTC. */
    public static String normalize(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return "UTC";
        }
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE",
                "Unknown timezone. Use an IANA name such as Europe/London or Africa/Lagos.");
        }
    }
}
