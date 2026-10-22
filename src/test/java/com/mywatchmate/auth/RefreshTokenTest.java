package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private final Instant now = Instant.parse("2026-10-22T06:00:00Z");
    private final RefreshToken token =
        new RefreshToken(new User("ada@example.com", "Ada"), "hash", UUID.randomUUID(), now.plusSeconds(60), "ua");

    @Test
    void isValidUntilTheMomentItExpires() {
        assertThat(token.isExpired(now)).isFalse();
        assertThat(token.isExpired(now.plusSeconds(59))).isFalse();
        assertThat(token.isExpired(now.plusSeconds(60))).isTrue();
        assertThat(token.isExpired(now.plusSeconds(61))).isTrue();
    }

    @Test
    void revokingKeepsTheFirstRevocationTime() {
        assertThat(token.isRevoked()).isFalse();

        token.revoke(now);
        token.revoke(now.plusSeconds(30));

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(now);
    }
}
