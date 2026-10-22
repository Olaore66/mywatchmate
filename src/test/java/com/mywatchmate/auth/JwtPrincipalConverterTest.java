package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class JwtPrincipalConverterTest {

    private final JwtPrincipalConverter converter = new JwtPrincipalConverter();

    @Test
    void userTokenBecomesUserPrincipalWithUserRole() {
        UUID userId = UUID.randomUUID();

        PrincipalAuthentication auth = converter.convert(jwt(userId.toString(), b -> b.claim("typ", "user")));

        assertThat(auth.getPrincipal()).isEqualTo(AuthPrincipal.user(userId));
        assertThat(auth.getPrincipal().isGuest()).isFalse();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo(userId.toString());
    }

    @Test
    void guestTokenBecomesGuestPrincipalWithGuestRole() {
        UUID participantId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();

        PrincipalAuthentication auth = converter.convert(
            jwt(participantId.toString(), b -> b.claim("typ", "guest").claim("sid", spaceId.toString())));

        assertThat(auth.getPrincipal()).isEqualTo(AuthPrincipal.guest(participantId, spaceId));
        assertThat(auth.getPrincipal().isGuest()).isTrue();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_GUEST");
    }

    @Test
    void unknownOrMissingTypeIsRejected() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> converter.convert(jwt(id, b -> b.claim("typ", "admin"))))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt(id, b -> b.claim("other", "x"))))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void nonUuidSubjectIsRejected() {
        assertThatThrownBy(() -> converter.convert(jwt("not-a-uuid", b -> b.claim("typ", "user"))))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void guestTokenWithoutSpaceIsRejected() {
        assertThatThrownBy(() -> converter.convert(jwt(UUID.randomUUID().toString(), b -> b.claim("typ", "guest"))))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(String subject, Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS256").subject(subject);
        claims.accept(builder);
        return builder.build();
    }
}
