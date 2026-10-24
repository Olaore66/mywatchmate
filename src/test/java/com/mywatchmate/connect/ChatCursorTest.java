package com.mywatchmate.connect;

import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatCursorTest {

    @Test
    void roundTripsToTheMicrosecond() {
        ChatCursor cursor = new ChatCursor(Instant.parse("2026-11-02T05:12:30.123456Z"), UUID.randomUUID());

        String encoded = cursor.encode();

        assertThat(ChatCursor.decode(encoded)).isEqualTo(cursor);
        assertThat(encoded).as("safe in a URL").matches("[A-Za-z0-9_-]+");
    }

    @Test
    void anythingElseIsRefused() {
        for (String bad : new String[] {"not base64 !", "bm9wZQ", "MTIzOm5vdC1hLXV1aWQ", ""}) {
            assertApiError(() -> ChatCursor.decode(bad), HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }
}
