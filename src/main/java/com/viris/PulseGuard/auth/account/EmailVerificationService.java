package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.common.exception.InvalidEmailTokenException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import com.viris.PulseGuard.enumeration.EmailTokenPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Proves an account's address is its owner's. Until then no email alert is sent to the
 * account (NotificationService): otherwise anyone could sign up with a stranger's address and
 * have alerts land in their inbox.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final EmailTokenRepository tokens;
    private final UserRepository users;
    private final EmailSendThrottle throttle;
    private final AccountEmailProperties properties;
    private final ApplicationEventPublisher events;

    public EmailVerificationService(EmailTokenRepository tokens, UserRepository users, EmailSendThrottle throttle,
                                    AccountEmailProperties properties, ApplicationEventPublisher events) {
        this.tokens = tokens;
        this.users = users;
        this.throttle = throttle;
        this.properties = properties;
        this.events = events;
    }

    /** At sign-up, in the registration transaction: the email goes out once the account exists. */
    @Transactional
    public void issue(User user) {
        tokens.deleteAllForUser(user.getId(), EmailTokenPurpose.VERIFY);
        String token = SecureTokens.newToken();
        EmailToken row = new EmailToken();
        row.setUser(user);
        row.setPurpose(EmailTokenPurpose.VERIFY);
        row.setEmail(user.getEmail());
        row.setTokenHash(SecureTokens.hash(token));
        row.setExpiresAt(Instant.now().plus(properties.verifyTokenTtl()));
        tokens.save(row);
        events.publishEvent(new AccountEmailEvents.VerificationRequested(
                user.getEmail(), user.getName(), token, row.getExpiresAt()));
    }

    /** "Resend email" from the banner. Already verified: nothing to send. */
    @Transactional
    public void resend(Long userId, String clientIp) {
        User user = users.findById(userId).orElseThrow();
        if (user.isEmailVerified()) {
            return;
        }
        if (!throttle.tryAcquire("verify", user.getEmail(), clientIp)) {
            throw new TooManyAttemptsException("Too many verification emails. Try again later.");
        }
        issue(user);
        log.info("Verification email re-sent for user {}", userId);
    }

    /**
     * Marks the address verified. The link must be for the address the account has now: a
     * link sent before an email change must not vouch for the new address.
     */
    @Transactional
    public void verify(String token) {
        EmailToken row = tokens.findForUpdate(SecureTokens.hash(token), EmailTokenPurpose.VERIFY)
                .orElseThrow(InvalidEmailTokenException::new);
        User user = row.getUser();
        tokens.deleteAllForUser(user.getId(), EmailTokenPurpose.VERIFY);
        if (row.getExpiresAt().isBefore(Instant.now()) || !row.getEmail().equals(user.getEmail())) {
            throw new InvalidEmailTokenException();
        }
        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(Instant.now());
            log.info("Email verified for user {}", user.getId());
        }
    }
}
