package com.mywatchmate.moderation;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.PaymentLink;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** Where a page of links ends: the last link's (statusChangedAt, id), opaque to the client. */
record ReviewCursor(Instant at, UUID id) {

    static ReviewCursor of(PaymentLink link) {
        return new ReviewCursor(link.getStatusChangedAt(), link.getId());
    }

    String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, at);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    static ReviewCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            return new ReviewCursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "That cursor isn't one we gave you.");
        }
    }
}
