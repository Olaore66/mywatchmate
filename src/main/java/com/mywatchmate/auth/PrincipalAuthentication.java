package com.mywatchmate.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** A verified access token, carrying an {@link AuthPrincipal} and ROLE_USER or ROLE_GUEST. */
public class PrincipalAuthentication extends AbstractAuthenticationToken {

    private final AuthPrincipal principal;
    private final Jwt jwt;

    public PrincipalAuthentication(AuthPrincipal principal, Jwt jwt) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.kind().name())));
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public AuthPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return jwt.getSubject();
    }
}
