package com.mywatchmate.auth;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/** Reads our claims ("sub", "typ", "sid") from a verified JWT into a {@link PrincipalAuthentication}. */
@Component
public class JwtPrincipalConverter implements Converter<Jwt, PrincipalAuthentication> {

    @Override
    public PrincipalAuthentication convert(Jwt jwt) {
        try {
            UUID subject = UUID.fromString(jwt.getSubject());
            String type = jwt.getClaimAsString(TokenService.CLAIM_TYPE);
            AuthPrincipal principal = switch (type == null ? "" : type) {
                case TokenService.TYPE_USER -> AuthPrincipal.user(subject);
                case TokenService.TYPE_GUEST ->
                    AuthPrincipal.guest(subject, UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_SPACE)));
                default -> throw new InvalidBearerTokenException("Unknown token type");
            };
            return new PrincipalAuthentication(principal, jwt);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Malformed token claims");
        }
    }
}
