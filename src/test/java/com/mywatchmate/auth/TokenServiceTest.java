package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

    private static final SecretKey KEY = new SecretKeySpec(
        "unit-test-signing-key-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final JwtProperties PROPS =
        new JwtProperties("unused", "mywatchmate", Duration.ofMinutes(15), Duration.ofDays(30));

    private final TokenService tokens = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), PROPS);
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void userTokenCarriesSubjectTypeIssuerAndFifteenMinuteLifetime() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokens.userAccessToken(userId));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("user");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("mywatchmate");
        assertThat(jwt.hasClaim("sid")).isFalse();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void guestTokenCarriesParticipantAndSpace() {
        UUID participantId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokens.guestAccessToken(participantId, spaceId));

        assertThat(jwt.getSubject()).isEqualTo(participantId.toString());
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("guest");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(spaceId.toString());
    }

    @Test
    void reportsAccessTokenLifetimeInSeconds() {
        assertThat(tokens.accessTokenSeconds()).isEqualTo(900);
    }

    @Test
    void opaqueTokensAreUrlSafeAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = tokens.newOpaqueToken();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+"); // 32 random bytes, base64url, no padding
            seen.add(token);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void hashIsSha256Hex() {
        // Standard SHA-256 test vector
        assertThat(tokens.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(tokens.hash("abc")).isEqualTo(tokens.hash("abc"));
        assertThat(tokens.hash("abd")).isNotEqualTo(tokens.hash("abc"));
    }
}
