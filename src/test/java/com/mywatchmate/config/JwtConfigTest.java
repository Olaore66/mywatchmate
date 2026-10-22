package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.auth.JwtProperties;
import com.mywatchmate.auth.TokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtConfigTest {

    private static final String SECRET = "unit-test-signing-key-at-least-32-bytes!";

    private final JwtConfig config = new JwtConfig();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"too-short", "exactly-thirty-one-characters!!"})
    void refusesToStartWithoutAStrongEnoughSecret(String secret) {
        assertThatThrownBy(() -> config.jwtSigningKey(props(secret, "mywatchmate", Duration.ofMinutes(15))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void acceptsAThirtyTwoByteSecret() {
        assertThat(config.jwtSigningKey(props("x".repeat(32), "mywatchmate", Duration.ofMinutes(15))).getAlgorithm())
            .isEqualTo("HmacSHA256");
    }

    @Test
    void decoderAcceptsOwnTokens() {
        JwtProperties props = props(SECRET, "mywatchmate", Duration.ofMinutes(15));
        SecretKey key = config.jwtSigningKey(props);
        UUID userId = UUID.randomUUID();

        String token = new TokenService(config.jwtEncoder(key), props).userAccessToken(userId);

        assertThat(config.jwtDecoder(key, props).decode(token).getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void decoderRejectsTokensFromAnotherIssuer() {
        JwtProperties ours = props(SECRET, "mywatchmate", Duration.ofMinutes(15));
        SecretKey key = config.jwtSigningKey(ours);
        String foreign = new TokenService(config.jwtEncoder(key), props(SECRET, "someone-else", Duration.ofMinutes(15)))
            .userAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> config.jwtDecoder(key, ours).decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void decoderRejectsTokensSignedWithAnotherKey() {
        JwtProperties ours = props(SECRET, "mywatchmate", Duration.ofMinutes(15));
        JwtProperties attacker = props("an-entirely-different-key-of-32-bytes!!", "mywatchmate", Duration.ofMinutes(15));
        String forged = new TokenService(config.jwtEncoder(config.jwtSigningKey(attacker)), attacker)
            .userAccessToken(UUID.randomUUID());

        JwtDecoder decoder = config.jwtDecoder(config.jwtSigningKey(ours), ours);
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void decoderRejectsExpiredTokens() {
        JwtProperties props = props(SECRET, "mywatchmate", Duration.ofMinutes(15));
        SecretKey key = config.jwtSigningKey(props);
        // Expired 5 minutes ago: beyond the decoder's 60-second clock-skew allowance
        Instant now = Instant.now();
        String token = config.jwtEncoder(key).encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder()
                .issuer("mywatchmate")
                .subject(UUID.randomUUID().toString())
                .claim("typ", "user")
                .issuedAt(now.minus(Duration.ofMinutes(10)))
                .expiresAt(now.minus(Duration.ofMinutes(5)))
                .build())).getTokenValue();

        assertThatThrownBy(() -> config.jwtDecoder(key, props).decode(token)).isInstanceOf(JwtException.class);
    }

    private static JwtProperties props(String secret, String issuer, Duration accessTtl) {
        return new JwtProperties(secret, issuer, accessTtl, Duration.ofDays(30));
    }
}
