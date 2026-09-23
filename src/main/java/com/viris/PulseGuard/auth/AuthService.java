package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.dto.AuthResponse;
import com.viris.PulseGuard.auth.dto.ChangePasswordRequest;
import com.viris.PulseGuard.auth.dto.LoginRequest;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.dto.UserResponse;
import com.viris.PulseGuard.auth.jwt.JwtService;
import com.viris.PulseGuard.auth.jwt.ParsedToken;
import com.viris.PulseGuard.auth.jwt.TokenType;
import com.viris.PulseGuard.auth.jwt.UserMapper;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.common.exception.EmailAlreadyUsedException;
import com.viris.PulseGuard.common.exception.InvalidCredentialsException;
import com.viris.PulseGuard.common.exception.PasswordUnchangedException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenDenylist denylist;
    private final LoginRateLimiter rateLimiter;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = UserMapper.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyUsedException(email);
        }

        User saved = users.save(UserMapper.toEntity(request, passwordEncoder.encode(request.password())));
        log.info("Registered user id={}", saved.getId());

        return issueTokens(UserPrincipal.from(saved), UserMapper.from(saved));
    }

    /**
     * Deliberately not {@code @Transactional}: authentication spends ~100ms in BCrypt plus two
     * Redis round-trips, and holding a pooled database connection across that buys nothing. The
     * single read it needs runs in its own transaction inside {@link AppUserDetailsService}.
     */
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = UserMapper.normalizeEmail(request.email());
        if (!rateLimiter.tryAcquire(email, clientIp)) {
            log.warn("Login rate limit hit from {}", clientIp);
            throw new TooManyAttemptsException();
        }

        UserPrincipal principal = authenticate(email, request.password());
        rateLimiter.reset(email, clientIp);

        // The provider already loaded this row; reuse it rather than querying again.
        return issueTokens(principal, UserMapper.from(principal.getUser()));
    }

    /**
     * Exchanges a refresh token for a new pair and revokes the one just used, so a stolen
     * refresh token is good for at most one use before the rotation invalidates it.
     *
     * @param accessToken the caller's current access token, or {@code null}. When supplied it is
     *                    revoked alongside the refresh token, so the replaced pair dies here
     *                    rather than staying usable until the access token's own expiry.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken, String accessToken) {
        ParsedToken parsed = parseOrReject(refreshToken);

        if (parsed.type() != TokenType.REFRESH
                || denylist.isRevoked(parsed.jti())
                || denylist.isRevokedForUser(parsed.userId(), parsed.issuedAt())) {
            throw new InvalidCredentialsException();
        }

        User user = users.findById(parsed.userId()).orElseThrow(InvalidCredentialsException::new);
        if (!user.isEnabled() || user.isLocked()) {
            throw new InvalidCredentialsException();
        }

        denylist.revoke(parsed.jti(), parsed.expiresAt());
        revokeCompanionAccessToken(accessToken, parsed.userId());
        return issueTokens(UserPrincipal.from(user), UserMapper.from(user));
    }

    /**
     * Retires the access token presented alongside the refresh token. Best-effort by design: the
     * header is optional and an expired or unreadable token has nothing left to revoke, so neither
     * case fails the refresh. Only the refresh token's own user is touched, so holding someone
     * else's access token does not let you revoke it.
     */
    private void revokeCompanionAccessToken(String accessToken, Long refreshUserId) {
        if (accessToken == null) {
            return;
        }
        try {
            ParsedToken parsed = jwtService.parse(accessToken);
            if (parsed.type() == TokenType.ACCESS && refreshUserId.equals(parsed.userId())) {
                denylist.revoke(parsed.jti(), parsed.expiresAt());
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Refresh ignored an unusable access token: {}", ex.getMessage());
        }
    }

    /**
     * Ends every session for the user, not just the tokens presented. A client that logs out
     * without returning its refresh token would otherwise leave it usable for its full lifetime.
     */
    public void logout(String accessToken) {
        try {
            ParsedToken parsed = jwtService.parse(accessToken);
            denylist.revoke(parsed.jti(), parsed.expiresAt());
            denylist.revokeAllForUser(parsed.userId(), Instant.now(), jwtService.sessionRetention());
            log.info("Logged out all sessions for user {}", parsed.userId());
        } catch (JwtException | IllegalArgumentException ex) {
            // Logout is idempotent: an unreadable or already-expired token is not an error.
            log.debug("Logout ignored an unusable token: {}", ex.getMessage());
        }
    }

    /**
     * Changes the password and ends every other session, because a password change usually means
     * the old one is compromised — leaving existing tokens alive would defeat the point.
     * <p>
     * The caller is handed a fresh pair so they are not logged out of the device they just used:
     * the cut-off is second-precision and compared strictly, so a token minted in the same second
     * survives while everything issued earlier does not.
     */
    @Transactional
    public AuthResponse changePassword(UserPrincipal principal, ChangePasswordRequest request) {
        User user = users.findById(principal.getUserId()).orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new PasswordUnchangedException();
        }

        // Managed entity: dirty checking writes the new hash at commit, so no save() call.
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));

        // Deliberately before commit, not in an AFTER_COMMIT listener like the alert side effects.
        // Redis is not part of this transaction, so one of the two orderings has to lose:
        //   before commit - a failed commit revokes sessions for a change that did not happen
        //                   (the user is logged out for nothing; annoying, recoverable)
        //   after commit  - a crash in between leaves every old session alive against the new
        //                   password (exactly what this feature exists to prevent)
        // Failing safe wins. Do not "fix" this to AFTER_COMMIT.
        denylist.revokeAllForUser(user.getId(), Instant.now(), jwtService.sessionRetention());
        log.info("Password changed for user {}; all sessions revoked", user.getId());

        return issueTokens(UserPrincipal.from(user), UserMapper.from(user));
    }

    /** The filter already loaded the current row; this reflects any change made since issue. */
    public UserResponse currentUser(UserPrincipal principal) {
        return UserMapper.from(principal.getUser());
    }

    private UserPrincipal authenticate(String normalizedEmail, String password) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, password));
            return (UserPrincipal) authentication.getPrincipal();
        } catch (AuthenticationException ex) {
            throw new InvalidCredentialsException();
        }
    }

    private ParsedToken parseOrReject(String token) {
        try {
            return jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidCredentialsException();
        }
    }

    private AuthResponse issueTokens(UserPrincipal principal, UserResponse user) {
        return AuthResponse.of(
                jwtService.generateAccessToken(principal),
                jwtService.generateRefreshToken(principal),
                jwtService.accessTokenSeconds(),
                user);
    }
}
