package com.mywatchmate.config;

import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.common.ProblemResponses;
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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API secured by our own JWTs. Public: sign-up/in, token refresh, guest join,
 * health, Swagger, and the WebSocket handshake (STOMP CONNECT is authenticated separately).
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_POSTS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
        "/api/v1/auth/guest", "/api/v1/auth/guest/refresh"
    };

    private static final String[] PUBLIC_PATHS = {
        "/actuator/health/**", "/actuator/info", "/ws/**",
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtPrincipalConverter jwtConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // Invite preview is public so the join screen works before sign-in
                .requestMatchers(HttpMethod.GET, "/api/v1/invites/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invites/*/join").hasRole("USER")
                .requestMatchers("/api/v1/spaces").hasRole("USER")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/upgrade").hasRole("GUEST")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").hasRole("USER")
                .requestMatchers("/api/v1/me/**", "/api/v1/me").hasRole("USER")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
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
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
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
