package com.viris.PulseGuard.apikey;

import com.viris.PulseGuard.auth.AppUserDetailsService;
import com.viris.PulseGuard.auth.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code Authorization: Bearer pg_live_…}. Runs before the JWT filter, which
 * then sees an authenticated request and steps aside. Like the JWT filter, it reloads the
 * user on every request, so disabling or locking the account stops its keys at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final AppUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && ApiKeySecrets.looksLikeKey(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String key) {
        try {
            Long userId = apiKeyService.authenticate(key).orElse(null);
            if (userId == null) {
                // Never log the key; not even its prefix identifies which one was tried.
                log.debug("Rejected unknown API key");
                return;
            }
            UserPrincipal principal = userDetailsService.loadByUserId(userId);
            if (!principal.isEnabled() || !principal.isAccountNonLocked()) {
                log.debug("Rejected API key for inactive user {}", userId);
                return;
            }
            ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(principal);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UsernameNotFoundException ex) {
            SecurityContextHolder.clearContext();
        }
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
