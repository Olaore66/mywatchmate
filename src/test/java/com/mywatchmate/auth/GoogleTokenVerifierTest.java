package com.mywatchmate.auth;

import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.auth.GoogleTokenVerifier.GoogleAccount;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Tokens signed with a key we pretend is Google's, checked the way real ones are. */
class GoogleTokenVerifierTest {

    private static final String OURS = "1234-web.apps.googleusercontent.com";
    private static KeyPair google;
    private static KeyPair stranger;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        google = generator.generateKeyPair();
        stranger = generator.generateKeyPair();
    }

    private static GoogleTokenVerifier verifier(List<String> clientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) google.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        return new GoogleTokenVerifier(new GoogleProperties(clientIds), decoder);
    }

    private static String token(KeyPair signer, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .audience(OURS)
            .subject("1092837465")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
            .claim("email", "Ada.Okafor@Gmail.com")
            .claim("email_verified", true)
            .claim("name", "Ada Okafor");
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(signer.getPrivate()));
        return jwt.serialize();
    }

    private static String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        return token(google, change);
    }

    @Test
    void aGenuineTokenGivesWhoSignedIn() throws Exception {
        GoogleAccount account = verifier(List.of(OURS)).verify(token(c -> { }));

        assertThat(account.subject()).isEqualTo("1092837465");
        assertThat(account.email()).isEqualTo("ada.okafor@gmail.com");
        assertThat(account.name()).isEqualTo("Ada Okafor");
    }

    @Test
    void bothOfGooglesIssuerSpellingsAndAnyOfOurClientIdsAreAccepted() throws Exception {
        GoogleTokenVerifier verifier = verifier(List.of("ios.apps.googleusercontent.com", OURS));

        assertThat(verifier.verify(token(c -> c.issuer("accounts.google.com"))).subject()).isEqualTo("1092837465");
        assertThat(verifier.verify(token(c -> c.claim("email_verified", "true"))).email()).isEqualTo("ada.okafor@gmail.com");
    }

    @Test
    void aTokenMadeForAnotherAppIsRefused() throws Exception {
        String elsewhere = token(c -> c.audience("someone-elses-app.apps.googleusercontent.com"));

        assertApiError(() -> verifier(List.of(OURS)).verify(elsewhere), HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void forgedExpiredOrNotFromGoogleIsRefused() throws Exception {
        GoogleTokenVerifier verifier = verifier(List.of(OURS));
        String forged = token(stranger, c -> { });
        String expired = token(c -> c.expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(5)))));
        String notGoogle = token(c -> c.issuer("https://evil.example.com"));
        String noExpiry = token(c -> c.expirationTime(null));

        for (String bad : new String[] {forged, expired, notGoogle, noExpiry, "not-a-token"}) {
            assertApiError(() -> verifier.verify(bad), HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
        }
    }

    @Test
    void anUnverifiedOrMissingEmailIsRefused() throws Exception {
        GoogleTokenVerifier verifier = verifier(List.of(OURS));
        String unverified = token(c -> c.claim("email_verified", false));
        String noEmail = token(c -> c.claim("email", null));

        assertApiError(() -> verifier.verify(unverified), HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED");
        assertApiError(() -> verifier.verify(noEmail), HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED");
    }

    @Test
    void withNoClientIdItIsSwitchedOff() throws Exception {
        String genuine = token(c -> { });

        assertApiError(() -> verifier(List.of()).verify(genuine), HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_SIGN_IN_DISABLED");
        assertThat(new GoogleProperties(List.of(" ", "")).enabled()).isFalse();
        assertThat(new GoogleProperties(null).clientIds()).isEmpty();
        assertThat(new GoogleProperties(List.of(" " + OURS + " ")).clientIds()).containsExactly(OURS);
    }
}
