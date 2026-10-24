package com.mywatchmate.connect;

import com.mywatchmate.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a history page ends: the oldest message's (createdAt, id), opaque to the client.
 * Two messages can share a timestamp, so the id breaks the tie and none is skipped or repeated.
 */
public record ChatCursor(Instant createdAt, UUID id) {

    public static ChatCursor of(ChatMessage message) {
        return new ChatCursor(message.getCreatedAt(), message.getId());
    }

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static ChatCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            return new ChatCursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "That 'before' cursor isn't one we gave you.");
        }
    }
}
