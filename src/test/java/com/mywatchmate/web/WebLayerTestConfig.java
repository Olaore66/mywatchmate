package com.mywatchmate.web;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.auth.JwtProperties;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.config.JwtConfig;
import com.mywatchmate.config.SecurityConfig;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * For {@code @WebMvcTest} controller tests: the real security rules and error format,
 * with services mocked. No database, no WebSocket broker.
 */
@TestConfiguration
@Import({SecurityConfig.class, JwtConfig.class, JwtPrincipalConverter.class})
@EnableConfigurationProperties(JwtProperties.class)
public class WebLayerTestConfig {

    public static RequestPostProcessor asUser(UUID userId) {
        return as(AuthPrincipal.user(userId), userId);
    }

    public static RequestPostProcessor asGuest(UUID participantId, UUID spaceId) {
        return as(AuthPrincipal.guest(participantId, spaceId), participantId);
    }

    private static RequestPostProcessor as(AuthPrincipal principal, UUID subject) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256").subject(subject.toString()).build();
        return authentication(new PrincipalAuthentication(principal, jwt));
    }
}
