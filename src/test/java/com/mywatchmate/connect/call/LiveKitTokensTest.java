package com.mywatchmate.connect.call;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveKitTokensTest {

    static final String KEY = "APItestkey12345";
    static final String SECRET = "test-livekit-secret-that-is-long-enough-123456";
    static final LiveKitProperties PROPS =
        new LiveKitProperties("wss://mywatchmate-test.livekit.cloud", KEY, SECRET, Duration.ofMinutes(10));

    private final LiveKitTokens tokens = new LiveKitTokens(PROPS);

    /** Signs a webhook the way LiveKit does. */
    static String webhookToken(String body, String key, String secret, Instant issuedAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(key)
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(Duration.ofMinutes(5))))
            .claim("sha256", LiveKitTokens.sha256(body))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JWTClaimsSet verified(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).as("signed with our secret").isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        return jwt.getJWTClaimsSet();
    }

    @Test
    void aJoinTokenLetsOnePersonIntoOneRoomWithCameraAndMic() throws Exception {
        JWTClaimsSet claims = verified(tokens.joinToken("space-1", "participant-1", "Bisi", "{\"guest\":true}", MONDAY_MIDNIGHT));

        assertThat(claims.getIssuer()).isEqualTo(KEY);
        assertThat(claims.getSubject()).isEqualTo("participant-1");
        assertThat(claims.getStringClaim("name")).isEqualTo("Bisi");
        assertThat(claims.getStringClaim("metadata")).isEqualTo("{\"guest\":true}");
        assertThat(claims.getNotBeforeTime().toInstant()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(MONDAY_MIDNIGHT.plus(Duration.ofMinutes(10)));
        assertThat(claims.getJSONObjectClaim("video")).containsAllEntriesOf(Map.of(
            "room", "space-1", "roomJoin", true, "canPublish", true, "canSubscribe", true, "canPublishData", true))
            .doesNotContainKey("roomAdmin");
    }

    @Test
    void anAdminTokenIsShortAndForOneRoom() throws Exception {
        JWTClaimsSet claims = verified(tokens.adminToken("space-1", MONDAY_MIDNIGHT));

        assertThat(claims.getIssuer()).isEqualTo(KEY);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(MONDAY_MIDNIGHT.plus(LiveKitTokens.ADMIN_TTL));
        assertThat(claims.getJSONObjectClaim("video")).isEqualTo(Map.of("room", "space-1", "roomAdmin", true));
    }

    @Test
    void aGenuineWebhookIsAccepted() throws Exception {
        String body = "{\"event\":\"participant_joined\"}";
        String token = webhookToken(body, KEY, SECRET, MONDAY_MIDNIGHT);

        assertThat(tokens.isGenuineWebhook(token, body, MONDAY_MIDNIGHT.plusSeconds(10))).isTrue();
        assertThat(tokens.isGenuineWebhook("Bearer " + token, body, MONDAY_MIDNIGHT.plusSeconds(10)))
            .as("with or without Bearer").isTrue();
    }

    @Test
    void aChangedBodyAnotherSecretOrKeyOrAnOldTokenIsRefused() throws Exception {
        String body = "{\"event\":\"participant_joined\"}";
        String token = webhookToken(body, KEY, SECRET, MONDAY_MIDNIGHT);

        assertThat(tokens.isGenuineWebhook(token, body + " ", MONDAY_MIDNIGHT)).as("body altered").isFalse();
        assertThat(tokens.isGenuineWebhook(webhookToken(body, KEY, "someone-elses-secret-also-long-enough-1234", MONDAY_MIDNIGHT),
            body, MONDAY_MIDNIGHT)).as("wrong secret").isFalse();
        assertThat(tokens.isGenuineWebhook(webhookToken(body, "APIother", SECRET, MONDAY_MIDNIGHT), body, MONDAY_MIDNIGHT))
            .as("another project's key").isFalse();
        assertThat(tokens.isGenuineWebhook(token, body, MONDAY_MIDNIGHT.plus(Duration.ofMinutes(7))))
            .as("expired").isFalse();
        assertThat(tokens.isGenuineWebhook(token, body, MONDAY_MIDNIGHT.minus(Duration.ofMinutes(2))))
            .as("not yet valid").isFalse();
    }

    @Test
    void aSmallClockDifferenceIsTolerated() throws Exception {
        String body = "{}";
        String token = webhookToken(body, KEY, SECRET, MONDAY_MIDNIGHT);

        assertThat(tokens.isGenuineWebhook(token, body, MONDAY_MIDNIGHT.minusSeconds(30))).isTrue();
        assertThat(tokens.isGenuineWebhook(token, body, MONDAY_MIDNIGHT.plus(Duration.ofMinutes(5)).plusSeconds(30))).isTrue();
    }

    @Test
    void missingOrGarbageHeadersAreRefused() throws Exception {
        assertThat(tokens.isGenuineWebhook(null, "{}", MONDAY_MIDNIGHT)).isFalse();
        assertThat(tokens.isGenuineWebhook("  ", "{}", MONDAY_MIDNIGHT)).isFalse();
        assertThat(tokens.isGenuineWebhook("not.a.jwt", "{}", MONDAY_MIDNIGHT)).isFalse();
        assertThat(tokens.isGenuineWebhook(webhookToken("{}", KEY, SECRET, MONDAY_MIDNIGHT), null, MONDAY_MIDNIGHT)).isFalse();

        // Signed correctly but without the body's hash
        JWTClaimsSet noHash = new JWTClaimsSet.Builder().issuer(KEY).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), noHash);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        assertThat(tokens.isGenuineWebhook(jwt.serialize(), "{}", MONDAY_MIDNIGHT)).isFalse();

        // An unsigned ("alg": "none") token
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer(KEY)
            .claim("sha256", LiveKitTokens.sha256("{}")).build()).serialize();
        assertThat(tokens.isGenuineWebhook(unsigned, "{}", MONDAY_MIDNIGHT)).isFalse();
    }

    @Test
    void settingsKnowWhenTheyreCompleteAndTheApiAddress() {
        assertThat(PROPS.configured()).isTrue();
        assertThat(PROPS.httpUrl()).isEqualTo("https://mywatchmate-test.livekit.cloud");
        assertThat(new LiveKitProperties("ws://localhost:7880/", KEY, SECRET, Duration.ofMinutes(10)).httpUrl())
            .isEqualTo("http://localhost:7880");
        assertThat(new LiveKitProperties("", KEY, SECRET, Duration.ofMinutes(10)).configured()).isFalse();
        assertThat(new LiveKitProperties("wss://x", null, SECRET, Duration.ofMinutes(10)).configured()).isFalse();
        assertThat(new LiveKitProperties("wss://x", KEY, " ", Duration.ofMinutes(10)).configured()).isFalse();
    }
}
