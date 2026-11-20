package com.mywatchmate.auth;

import com.mywatchmate.common.ApiException;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks a Google ID token from "Sign in with Google": signed by Google (its public keys, fetched and cached),
 * issued by Google, made for one of OUR client ids, not expired, and for a verified email. The client id check
 * matters most: without it, a token another website got for its own users would sign them in here.
 */
@Component
public class GoogleTokenVerifier {

    static final String GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs";
    static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    /** Who Google says signed in. */
    public record GoogleAccount(String subject, String email, String name) {
    }

    private final GoogleProperties props;
    private final NimbusJwtDecoder decoder;

    @Autowired
    public GoogleTokenVerifier(GoogleProperties props) {
        this(props, NimbusJwtDecoder.withJwkSetUri(GOOGLE_KEYS).jwsAlgorithm(SignatureAlgorithm.RS256).build());
    }

    GoogleTokenVerifier(GoogleProperties props, NimbusJwtDecoder decoder) {
        this.props = props;
        this.decoder = decoder;
        OAuth2TokenValidator<Jwt> fromGoogle = jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))
            ? OAuth2TokenValidatorResult.success() : invalid("not issued by Google");
        OAuth2TokenValidator<Jwt> forUs = jwt -> jwt.getAudience() != null
            && jwt.getAudience().stream().anyMatch(props.clientIds()::contains)
            ? OAuth2TokenValidatorResult.success() : invalid("not issued for this app");
        OAuth2TokenValidator<Jwt> expires = jwt -> jwt.getExpiresAt() != null
            ? OAuth2TokenValidatorResult.success() : invalid("no expiry");
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), expires, fromGoogle, forUs));
    }

    public GoogleAccount verify(String idToken) {
        if (!props.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_SIGN_IN_DISABLED",
                "Sign in with Google isn't switched on yet.");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("INVALID_GOOGLE_TOKEN", "That Google sign-in didn't work. Please try again.");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank() || jwt.getSubject() == null || !isTrue(jwt.getClaim("email_verified"))) {
            throw ApiException.unauthorized("GOOGLE_EMAIL_NOT_VERIFIED",
                "Your Google account's email isn't verified. Verify it with Google, or sign up with email instead.");
        }
        return new GoogleAccount(jwt.getSubject(), email.strip().toLowerCase(Locale.ROOT), jwt.getClaimAsString("name"));
    }

    private static boolean isTrue(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equals(claim);
    }

    private static OAuth2TokenValidatorResult invalid(String why) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", why, null));
    }
}
