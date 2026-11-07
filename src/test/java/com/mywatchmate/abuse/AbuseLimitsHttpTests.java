package com.mywatchmate.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The limits over real HTTP, through Tomcat's proxy handling, as they run behind Caddy: the test is the proxy
 * (it connects from 127.0.0.1, a trusted private address) and says who the client is in X-Forwarded-For.
 * Each test uses its own made-up client addresses, so they don't share budgets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"mywatchmate.abuse.enabled=true", "mywatchmate.allowed-origins=https://www.mywatchmates.com"})
class AbuseLimitsHttpTests {

    private static final AtomicInteger NEXT = new AtomicInteger(10);
    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** A fresh made-up public address (TEST-NET-3). */
    private static String client() {
        int n = NEXT.getAndIncrement();
        return "203.0." + (113 + n / 250) + "." + (n % 250 + 1);
    }

    private HttpResponse<String> post(String path, String xff, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", xff)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String xff) throws Exception {
        return post("/api/v1/auth/register", xff, """
            {"email":"limit-%s@example.com","password":"%s","displayName":"Ada"}""".formatted(UUID.randomUUID(), PASSWORD));
    }

    private HttpResponse<String> login(String xff, String email, String password) throws Exception {
        return post("/api/v1/auth/login", xff, "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private static void assertLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("\"code\":\"TOO_MANY_ATTEMPTS\"");
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(s -> assertThat(Long.parseLong(s)).isPositive());
    }

    @Test
    void sixSignUpsAnHourFromOneAddressIsOneTooMany() throws Exception {
        String bot = client();
        for (int i = 0; i < 5; i++) {
            assertThat(register(bot).statusCode()).isEqualTo(201);
        }

        assertLimited(register(bot));
        assertThat(register(client()).statusCode()).as("someone else signs up fine").isEqualTo(201);
    }

    @Test
    void aClientCantDodgeTheLimitByWritingItsOwnForwardedFor() throws Exception {
        String bot = client();
        // The proxy appends the real address; whatever the client wrote before it is ignored
        for (int i = 0; i < 5; i++) {
            assertThat(register("10.9.9." + i + ", " + client() + ", " + bot).statusCode())
                .as("only the right-most untrusted address counts").isEqualTo(201);
        }
        // Fresh fake addresses on the left, same real one on the right: still the same client
        assertLimited(register(client() + ", " + bot));
    }

    @Test
    void wrongPasswordsBlockThatAccountFromThatAddressOnly() throws Exception {
        String home = client();
        String email = "victim-" + UUID.randomUUID() + "@example.com";
        assertThat(post("/api/v1/auth/register", home, """
            {"email":"%s","password":"%s","displayName":"Ada"}""".formatted(email, PASSWORD)).statusCode()).isEqualTo(201);
        String attacker = client();

        for (int i = 0; i < 5; i++) {
            assertThat(login(attacker, email, "guess-" + i).statusCode()).isEqualTo(401);
        }
        // Even the right password is refused from there now, and the password isn't even checked
        assertLimited(login(attacker, email, PASSWORD));

        // The owner, from their own address, isn't locked out
        assertThat(login(home, email, PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void anAccountAttackedFromManyAddressesSlowsDownEverywhereButNotForever() throws Exception {
        String email = "spread-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 20; i++) {
            assertThat(login(client(), email, "guess").statusCode()).isEqualTo(401);
        }

        assertLimited(login(client(), email, "guess"));
        // Unknown and known emails behave the same, so blocking doesn't reveal who has an account
        assertThat(login(client(), "other-" + UUID.randomUUID() + "@example.com", "guess").statusCode()).isEqualTo(401);
    }

    @Test
    void aSuccessClearsThatAddresssFailures() throws Exception {
        String home = client();
        String email = "typo-" + UUID.randomUUID() + "@example.com";
        post("/api/v1/auth/register", home, """
            {"email":"%s","password":"%s","displayName":"Ada"}""".formatted(email, PASSWORD));

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                assertThat(login(home, email, "typo").statusCode()).isEqualTo(401);
            }
            assertThat(login(home, email, PASSWORD).statusCode()).as("round %d", round).isEqualTo(200);
        }
    }

    @Test
    void theBrowserCanReadThe429AndPreflightsArentCounted() throws Exception {
        String bot = client();
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://www.mywatchmates.com")
                .header("Access-Control-Request-Method", "POST")
                .header("X-Forwarded-For", bot).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(preflight.statusCode()).isEqualTo(200);
        }
        for (int i = 0; i < 5; i++) {
            register(bot);
        }

        HttpResponse<String> limited = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.mywatchmates.com")
            .header("X-Forwarded-For", bot)
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

        assertLimited(limited);
        assertThat(limited.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://www.mywatchmates.com");
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers")).hasValueSatisfying(h -> assertThat(h).contains("Retry-After"));
    }

    @Test
    void theWebSocketHandshakeIsLimitedToo() throws Exception {
        String bot = client();
        for (int i = 0; i < 30; i++) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws"))
                .header("X-Forwarded-For", bot).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isNotEqualTo(429);
        }

        assertLimited(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws"))
            .header("X-Forwarded-For", bot).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void behindTheHttpsProxySwaggerCallsTheApiOverHttps() throws Exception {
        // Caddy reaches the app over plain HTTP and says the original request was HTTPS
        HttpResponse<String> docs = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "51-24-72-13.sslip.io")
            .header("X-Forwarded-For", client()).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("\"url\":\"https://51-24-72-13.sslip.io\"");
        // The limits show up in the docs: every guarded endpoint documents its 429
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(docs.body(),
            "$.paths['/api/v1/auth/register'].post.responses['429'].description")).contains("TOO_MANY_ATTEMPTS");
        assertThat(com.jayway.jsonpath.JsonPath.<Object>read(docs.body(),
            "$.paths['/api/v1/auth/login'].post.responses['429'].headers['Retry-After']")).isNotNull();
    }
}
