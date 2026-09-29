package com.viris.PulseGuard.auth.reset;

import com.viris.PulseGuard.apikey.ApiKeyRepository;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.account.AccountEmailProperties;
import com.viris.PulseGuard.auth.account.EmailSendThrottle;
import com.viris.PulseGuard.auth.account.SecureTokens;
import com.viris.PulseGuard.auth.jwt.JwtService;
import com.viris.PulseGuard.auth.jwt.UserMapper;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.common.exception.InvalidResetTokenException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * "Forgot password": email a single-use link, then let its holder set a new password.
 *
 * <p>Account enumeration: the request answers the same way for every address, and the email
 * is sent after commit on another thread, so neither the response nor its timing tells an
 * attacker whether an address is registered.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenDenylist denylist;
    private final JwtService jwtService;
    private final EmailSendThrottle throttle;
    private final AccountEmailProperties properties;
    private final ApplicationEventPublisher events;
    private final ApiKeyRepository apiKeys;

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, TokenDenylist denylist, JwtService jwtService,
                                EmailSendThrottle throttle, AccountEmailProperties properties,
                                ApplicationEventPublisher events, ApiKeyRepository apiKeys) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.denylist = denylist;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.properties = properties;
        this.events = events;
        this.apiKeys = apiKeys;
    }

    /** Emails a reset link if the address belongs to an active account; otherwise does nothing. */
    @Transactional
    public void requestReset(String rawEmail, String clientIp) {
        String email = UserMapper.normalizeEmail(rawEmail);
        if (!throttle.tryAcquire("reset", email, clientIp)) {
            throw new TooManyAttemptsException("Too many reset requests. Try again later.");
        }
        Instant now = Instant.now();
        tokens.deleteExpired(now);

        User user = users.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled()) {
            log.debug("Password reset requested for an unknown or disabled address");
            return;
        }
        // One live link per account: a new request retires any earlier one.
        tokens.deleteAllForUser(user.getId());

        String token = SecureTokens.newToken();
        PasswordResetToken row = new PasswordResetToken();
        row.setUser(user);
        row.setTokenHash(SecureTokens.hash(token));
        row.setExpiresAt(now.plus(properties.resetTokenTtl()));
        tokens.save(row);

        events.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), user.getName(), token, row.getExpiresAt()));
        log.info("Password reset requested for user {}", user.getId());
    }

    /**
     * Sets a new password from a link. Every existing session is then revoked, as on a
     * password change: whoever else was signed in (possibly why the user is resetting) is out.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken row = tokens.findForUpdate(SecureTokens.hash(token))
                .orElseThrow(InvalidResetTokenException::new);
        User user = row.getUser();
        // Consumed before any check can fail: an expired link is also gone for good.
        tokens.deleteAllForUser(user.getId());
        if (row.getExpiresAt().isBefore(Instant.now()) || !user.isEnabled()) {
            throw new InvalidResetTokenException();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Before commit, for the reason spelled out in AuthService#changePassword.
        denylist.revokeAllForUser(user.getId(), Instant.now(), jwtService.sessionRetention());
        // A reset is the "I lost control of my account" path: keys go too.
        int keys = apiKeys.deleteAllForUser(user.getId());
        log.info("Password reset for user {}; all sessions and {} API key(s) revoked", user.getId(), keys);
    }
}
