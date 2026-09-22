package com.viris.PulseGuard.auth.jwt;

import com.viris.PulseGuard.auth.AppUserDetailsService;
import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code Authorization: Bearer <token>}.
 * <p>
 * The token proves identity; everything the application authorizes on — role, account state —
 * is loaded fresh from the database. That costs one primary-key lookup per request and means a
 * demotion, lock or deletion takes effect on the very next call instead of at token expiry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenDenylist denylist;
    private final AppUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        // No token is not an error here: the filter chain decides what needs authentication.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            ParsedToken parsed = jwtService.parse(token);

            // A refresh token must not open API endpoints; it only buys a new access token.
            if (parsed.type() != TokenType.ACCESS) {
                log.debug("Rejected {} token on an API request", parsed.type());
                return;
            }
            if (denylist.isRevoked(parsed.jti())
                    || denylist.isRevokedForUser(parsed.userId(), parsed.issuedAt())) {
                log.debug("Rejected revoked token for user {}", parsed.userId());
                return;
            }

            UserPrincipal principal = userDetailsService.loadByUserId(parsed.userId());
            if (!principal.isEnabled() || !principal.isAccountNonLocked()) {
                log.debug("Rejected token for inactive user {}", parsed.userId());
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | UsernameNotFoundException | IllegalArgumentException ex) {
            // Never log the token itself.
            log.debug("Rejected bearer token: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
