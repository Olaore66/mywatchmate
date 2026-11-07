package com.mywatchmate.config;

import com.mywatchmate.abuse.AbuseProperties;
import com.mywatchmate.abuse.RequestGuard;
import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.common.ProblemResponses;
import com.mywatchmate.common.RateLimiters;
import com.mywatchmate.connect.call.LiveKitWebhookController;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Stateless API secured by our own JWTs. Public: sign-up/in, token refresh, guest join,
 * health, Swagger, the WebSocket handshake (STOMP CONNECT is authenticated separately), and webhooks /
 * internal endpoints that check their own signatures.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_POSTS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
        "/api/v1/auth/guest", "/api/v1/auth/guest/refresh",
        "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset", "/api/v1/auth/email/verify"
    };

    private static final String[] PUBLIC_PATHS = {
        "/actuator/health/**", "/actuator/info", "/ws/**",
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", DocsController.PATH
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtPrincipalConverter jwtConverter, AbuseProperties abuse,
                                            RateLimiters limits) throws Exception {
        http
            // Per-IP limits on sign-in, sign-up, guest join, emails and the WebSocket handshake. After CORS, so
            // browsers can read the 429; before authentication, so floods cost no token checks or hashing
            .addFilterAfter(new RequestGuard(abuse, limits), CorsFilter.class)
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // Invite preview is public so the join screen works before sign-in
                .requestMatchers(HttpMethod.GET, "/api/v1/invites/*").permitAll()
                // Clock sync for playback; just the time, nothing private
                .requestMatchers(HttpMethod.GET, "/api/v1/time").permitAll()
                // Our own services (AI worker): checked against a service token in the controller, not a user token
                .requestMatchers("/api/v1/internal/**").permitAll()
                // LiveKit's webhook: checked against LiveKit's signature in the controller
                .requestMatchers(HttpMethod.POST, LiveKitWebhookController.PATH).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invites/*/join").hasRole("USER")
                .requestMatchers("/api/v1/spaces").hasRole("USER")
                .requestMatchers("/api/v1/spaces/*/reminders").hasRole("USER")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/upgrade").hasRole("GUEST")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout", "/api/v1/auth/email/resend").hasRole("USER")
                .requestMatchers("/api/v1/me/**", "/api/v1/me").hasRole("USER")
                // Staff screens: signed-in users here; the staff role itself is checked in the database on every call
                .requestMatchers("/api/v1/admin/**").hasRole("USER")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .bearerTokenResolver(userTokens())
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                .authenticationEntryPoint(unauthenticated())
                .accessDeniedHandler(forbidden()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(unauthenticated())
                .accessDeniedHandler(forbidden()));
        return http.build();
    }

    /** BCrypt by default; stored hashes are prefixed ({bcrypt}) so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${mywatchmate.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // So the app can read how long to wait after a 429
        cors.setExposedHeaders(List.of("Retry-After"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /**
     * User access tokens, from the Authorization header. Not on LiveKit's webhook: its Authorization header is
     * LiveKit's own signed token, which must reach the controller instead of failing as a bad login.
     */
    private static BearerTokenResolver userTokens() {
        DefaultBearerTokenResolver users = new DefaultBearerTokenResolver();
        return request -> LiveKitWebhookController.PATH.equals(request.getRequestURI()) ? null : users.resolve(request);
    }

    private static AuthenticationEntryPoint unauthenticated() {
        return (request, response, ex) -> ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED", "Sign in required: the access token is missing, expired or invalid.");
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, ex) -> ProblemResponses.write(request, response, HttpStatus.FORBIDDEN,
            "FORBIDDEN", "You don't have access to this.");
    }
}
