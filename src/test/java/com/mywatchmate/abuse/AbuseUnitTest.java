package com.mywatchmate.abuse;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.common.LocalFailureCounters;
import com.mywatchmate.common.LocalRateLimiters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AbuseUnitTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        private Instant now = MONDAY_MIDNIGHT;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static AbuseProperties.Limit limit(int max, Duration window) {
        return new AbuseProperties.Limit(max, window);
    }

    private static AbuseProperties props(boolean enabled) {
        return new AbuseProperties(enabled, List.of(
            new AbuseProperties.Rule("sign-up", "POST", List.of("/api/v1/auth/register"),
                List.of(limit(2, Duration.ofMinutes(1)), limit(3, Duration.ofHours(1)))),
            new AbuseProperties.Rule("ws", null, List.of("/ws/**"), List.of(limit(1, Duration.ofMinutes(1))))),
            new AbuseProperties.Login(limit(3, Duration.ofMinutes(15)), limit(5, Duration.ofHours(1))));
    }

    @Nested
    class TheRequestGuard {

        private final ManualClock clock = new ManualClock();
        private final AtomicInteger passedOn = new AtomicInteger();
        private final FilterChain chain = (request, response) -> passedOn.incrementAndGet();

        private MockHttpServletResponse send(RequestGuard guard, String method, String path, String ip) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            guard.doFilter(request, response, chain);
            return response;
        }

        @Test
        void overAnyOfARulesLimitsIs429WithThatLimitsWait() throws Exception {
            RequestGuard guard = new RequestGuard(props(true), new LocalRateLimiters(clock));

            assertThat(send(guard, "POST", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(send(guard, "POST", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            MockHttpServletResponse third = send(guard, "POST", "/api/v1/auth/register", "1.1.1.1");
            assertThat(third.getStatus()).isEqualTo(429);
            assertThat(third.getHeader("Retry-After")).isEqualTo("60");
            assertThat(third.getContentAsString()).contains("\"code\":\"TOO_MANY_ATTEMPTS\"");

            // The minute passes; the hourly limit (3) then stops the 4th, with the hour's wait
            clock.advance(Duration.ofMinutes(1));
            assertThat(send(guard, "POST", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            MockHttpServletResponse fifth = send(guard, "POST", "/api/v1/auth/register", "1.1.1.1");
            assertThat(fifth.getStatus()).isEqualTo(429);
            assertThat(Long.parseLong(fifth.getHeader("Retry-After"))).isEqualTo(59 * 60);
            assertThat(passedOn).hasValue(3);
        }

        @Test
        void otherAddressesMethodsAndPathsArentAffected() throws Exception {
            RequestGuard guard = new RequestGuard(props(true), new LocalRateLimiters(clock));
            for (int i = 0; i < 3; i++) {
                send(guard, "POST", "/api/v1/auth/register", "1.1.1.1");
            }

            assertThat(send(guard, "POST", "/api/v1/auth/register", "2.2.2.2").getStatus()).isEqualTo(200);
            assertThat(send(guard, "GET", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(send(guard, "OPTIONS", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(send(guard, "POST", "/api/v1/spaces", "1.1.1.1").getStatus()).isEqualTo(200);
        }

        @Test
        void aRuleWithoutAMethodCoversEveryMethodAndSubPath() throws Exception {
            RequestGuard guard = new RequestGuard(props(true), new LocalRateLimiters(clock));

            assertThat(send(guard, "GET", "/ws/info", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(send(guard, "POST", "/ws/abc/xhr", "1.1.1.1").getStatus()).isEqualTo(429);
        }

        @Test
        void switchedOffItLetsEverythingThrough() throws Exception {
            RequestGuard guard = new RequestGuard(props(false), new LocalRateLimiters(clock));
            for (int i = 0; i < 10; i++) {
                assertThat(send(guard, "POST", "/api/v1/auth/register", "1.1.1.1").getStatus()).isEqualTo(200);
            }
        }
    }

    @Nested
    class SignInFailures {

        private final ManualClock clock = new ManualClock();
        private final LoginAttempts attempts = new LoginAttempts(props(true), new LocalFailureCounters(clock));

        @Test
        void aFewWrongPasswordsBlockThatAccountFromThatAddress() {
            for (int i = 0; i < 3; i++) {
                attempts.check("ada@example.com", "1.1.1.1");
                attempts.failed("ada@example.com", "1.1.1.1");
            }

            assertThatThrownBy(() -> attempts.check("ADA@example.com ", "1.1.1.1"))
                .isInstanceOfSatisfying(TooManyAttempts.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(15));
                });
            assertThatCode(() -> attempts.check("ada@example.com", "2.2.2.2")).as("the owner elsewhere").doesNotThrowAnyException();
            assertThatCode(() -> attempts.check("bisi@example.com", "1.1.1.1")).as("another account").doesNotThrowAnyException();

            clock.advance(Duration.ofMinutes(15));
            assertThatCode(() -> attempts.check("ada@example.com", "1.1.1.1")).as("it clears by itself").doesNotThrowAnyException();
        }

        @Test
        void aSuccessClearsThatAddressOnly() {
            // A high account-wide limit, so only the per-address counts matter here
            LoginAttempts attempts = new LoginAttempts(new AbuseProperties(true, List.of(),
                new AbuseProperties.Login(limit(3, Duration.ofMinutes(15)), limit(50, Duration.ofHours(1)))),
                new LocalFailureCounters(clock));
            for (int i = 0; i < 2; i++) {
                attempts.failed("ada@example.com", "1.1.1.1");
                attempts.failed("ada@example.com", "2.2.2.2");
            }

            attempts.succeeded("ada@example.com", "1.1.1.1");
            attempts.failed("ada@example.com", "1.1.1.1");
            attempts.failed("ada@example.com", "2.2.2.2");

            assertThatCode(() -> attempts.check("ada@example.com", "1.1.1.1")).doesNotThrowAnyException();
            assertApiError(() -> attempts.check("ada@example.com", "2.2.2.2"), HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
        }

        @Test
        void manyAddressesBlockTheAccountEverywhereUntilTheOldestFailureAges() {
            for (int i = 0; i < 5; i++) {
                attempts.failed("ada@example.com", "10.0.0." + i);
                clock.advance(Duration.ofMinutes(1));
            }

            assertThatThrownBy(() -> attempts.check("ada@example.com", "9.9.9.9"))
                .isInstanceOfSatisfying(TooManyAttempts.class,
                    e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(55)));
            clock.advance(Duration.ofMinutes(55));
            assertThatCode(() -> attempts.check("ada@example.com", "9.9.9.9")).doesNotThrowAnyException();
        }

        @Test
        void switchedOffNothingIsCounted() {
            LoginAttempts off = new LoginAttempts(props(false), new LocalFailureCounters(clock));
            for (int i = 0; i < 10; i++) {
                off.failed("ada@example.com", "1.1.1.1");
            }
            off.succeeded("ada@example.com", "1.1.1.1");

            assertThatCode(() -> off.check("ada@example.com", "1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        void withoutLoginSettingsSensibleDefaultsApplyButNothingIsChecked() {
            LoginAttempts defaults = new LoginAttempts(new AbuseProperties(true, null, null), new LocalFailureCounters(clock));
            for (int i = 0; i < 10; i++) {
                defaults.failed("ada@example.com", "1.1.1.1");
            }
            assertThatCode(() -> defaults.check("ada@example.com", "1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        void emailsAreOnlyKeptHashed() {
            assertThat(LoginAttempts.account(" Ada@Example.com ")).isEqualTo(LoginAttempts.account("ada@example.com"))
                .hasSize(64).doesNotContain("ada");
        }
    }

    @Nested
    class TheApiDocs {

        private static Operation operation() {
            return new Operation().responses(new ApiResponses());
        }

        @Test
        void guardedEndpointsDocumentTheir429() {
            Operation register = operation();
            Operation registerGet = operation();
            Operation spaces = operation();
            Operation login = operation();
            login.getResponses().addApiResponse("429", new io.swagger.v3.oas.models.responses.ApiResponse().description("own words"));
            OpenAPI api = new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/auth/register", new PathItem().post(register).get(registerGet))
                .addPathItem("/api/v1/spaces", new PathItem().post(spaces))
                .addPathItem("/ws/x", new PathItem().get(login)));

            new AbuseApiDocs(props(true)).customise(api);
            new AbuseApiDocs(props(true)).customise(api);

            assertThat(register.getResponses()).containsKey("429");
            assertThat(register.getResponses().get("429").getHeaders()).containsKey("Retry-After");
            assertThat(registerGet.getResponses()).doesNotContainKey("429");
            assertThat(spaces.getResponses()).doesNotContainKey("429");
            assertThat(login.getResponses().get("429").getDescription()).isEqualTo("own words");
            assertThat(login.getResponses().get("429").getHeaders()).containsKey("Retry-After");
        }

        @Test
        void switchedOffOrEmptyNothingChanges() {
            Operation register = operation();
            OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/v1/auth/register", new PathItem().post(register)));

            new AbuseApiDocs(props(false)).customise(api);
            new AbuseApiDocs(props(true)).customise(new OpenAPI());
            new AbuseApiDocs(props(true)).customise(new OpenAPI().paths(new Paths()
                .addPathItem("/ws", new PathItem().get(new Operation()))));

            assertThat(register.getResponses()).doesNotContainKey("429");
        }
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUp() {
        assertThat(TooManyAttempts.seconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(TooManyAttempts.seconds(Duration.ofMillis(1001))).isEqualTo(2);
        assertThat(TooManyAttempts.seconds(Duration.ofMinutes(15))).isEqualTo(900);
        assertThat(TooManyAttempts.seconds(Duration.ZERO)).isEqualTo(1);
    }
}
