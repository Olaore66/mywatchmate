package com.mywatchmate.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret     HMAC signing key, at least 32 bytes; from JWT_SECRET, never committed
 * @param issuer     "iss" claim stamped on and required in every token
 * @param accessTtl  how long an access token is valid
 * @param refreshTtl how long a user refresh token is valid
 */
@ConfigurationProperties("mywatchmate.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {
}
