package com.mywatchmate.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates signed access tokens and the random opaque tokens (refresh, guest device)
 * that we only ever store as SHA-256 hashes.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_SPACE = "sid";
    static final String TYPE_USER = "user";
    static final String TYPE_GUEST = "guest";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public String userAccessToken(UUID userId) {
        return encode(userId, TYPE_USER, null);
    }

    public String guestAccessToken(UUID participantId, UUID spaceId) {
        return encode(participantId, TYPE_GUEST, spaceId);
    }

    public long accessTokenSeconds() {
        return props.accessTtl().toSeconds();
    }

    /** 256 bits of randomness, URL-safe; handed to the client once and never stored as-is. */
    public String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String opaqueToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(opaqueToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String encode(UUID subject, String type, UUID spaceId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(subject.toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.accessTtl()))
            .claim(CLAIM_TYPE, type);
        if (spaceId != null) {
            claims.claim(CLAIM_SPACE, spaceId.toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
