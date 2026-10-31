package com.mywatchmate.connect.call;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LiveKit's tokens are JWTs signed (HS256) with our API secret: join tokens for the apps, short admin
 * tokens for our own calls to LiveKit, and the token on each webhook that proves LiveKit sent it.
 */
@Component
@RequiredArgsConstructor
public class LiveKitTokens {

    static final Duration ADMIN_TTL = Duration.ofMinutes(5);
    // LiveKit's clock and ours may differ a little
    static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final LiveKitProperties props;

    /** Lets one person into one call room: see and hear everyone, and send their own camera and mic. */
    public String joinToken(String room, String identity, String name, String metadata, Instant now) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);
        return sign(new JWTClaimsSet.Builder()
            .issuer(props.apiKey())
            .subject(identity)
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(props.tokenTtl())))
            .claim("name", name)
            .claim("metadata", metadata)
            .claim("video", video)
            .build());
    }

    /** For our backend's own requests to LiveKit about one room (who's in it, remove someone). */
    public String adminToken(String room, Instant now) {
        return sign(new JWTClaimsSet.Builder()
            .issuer(props.apiKey())
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ADMIN_TTL)))
            .claim("video", Map.of("room", room, "roomAdmin", true))
            .build());
    }

    /**
     * Did LiveKit send this webhook? Its Authorization header is a JWT signed with our secret, issued by our
     * key, and carrying the SHA-256 of the exact body, so a forged or altered request fails.
     */
    public boolean isGenuineWebhook(String authorization, String body, Instant now) {
        if (authorization == null || authorization.isBlank() || body == null) {
            return false;
        }
        String token = authorization.strip();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).strip();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret()))) {
                return false;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!props.apiKey().equals(claims.getIssuer())) {
                return false;
            }
            if (claims.getExpirationTime() != null && now.minus(CLOCK_SKEW).isAfter(claims.getExpirationTime().toInstant())) {
                return false;
            }
            if (claims.getNotBeforeTime() != null && now.plus(CLOCK_SKEW).isBefore(claims.getNotBeforeTime().toInstant())) {
                return false;
            }
            String claimed = claims.getStringClaim("sha256");
            return claimed != null && MessageDigest.isEqual(
                claimed.getBytes(StandardCharsets.UTF_8), sha256(body).getBytes(StandardCharsets.UTF_8));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return false;
        }
    }

    static String sha256(String body) {
        try {
            return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Couldn't sign a LiveKit token", e);
        }
    }

    private byte[] secret() {
        return props.apiSecret().getBytes(StandardCharsets.UTF_8);
    }
}
