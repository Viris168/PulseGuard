package com.viris.PulseGuard.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Security principal backed by a {@link User}.
 * <p>
 * Holds the entity it was built from, so a caller that already authenticated does not have to
 * load the same row again. The token never carries any of this — it is read per request.
 */
@Getter
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final transient User user;

    public static UserPrincipal from(User user) {
        // Spring's hasRole() expects the ROLE_ prefix; the enum stores the bare name.
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                user.isEnabled(),
                !user.isLocked(),
                user
        );
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }
}
