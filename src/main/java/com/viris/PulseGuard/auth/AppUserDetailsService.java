package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.jwt.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    /** The "username" is the email, normalized the same way it was at registration. */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmail(UserMapper.normalizeEmail(email))
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("No user for email"));
    }

    /**
     * Loads by primary key, for requests already carrying a verified token. Reading the user
     * again is what keeps role and account state current rather than frozen at issue time.
     */
    @Transactional(readOnly = true)
    public UserPrincipal loadByUserId(Long userId) {
        return users.findById(userId)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("No user for id"));
    }
}
