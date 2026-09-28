package com.viris.PulseGuard.apikey;

import com.viris.PulseGuard.auth.UserPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request authenticated by an API key rather than a session. Its own type so SecurityConfig
 * can keep keys away from account-security routes; controllers still see a plain UserPrincipal.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final UserPrincipal principal;

    public ApiKeyAuthenticationToken(UserPrincipal principal) {
        super(principal.getAuthorities());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // never keep the key around
    }

    @Override
    public UserPrincipal getPrincipal() {
        return principal;
    }
}
