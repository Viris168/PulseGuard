package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.jwt.UserMapper;
import com.viris.PulseGuard.common.exception.EmailAlreadyUsedException;
import com.viris.PulseGuard.common.exception.IncorrectPasswordException;
import com.viris.PulseGuard.common.exception.InvalidEmailTokenException;
import com.viris.PulseGuard.common.exception.InvalidMonitorException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.EmailTokenPurpose;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Changing the sign-in email. Two steps: the new address must confirm through a link before
 * anything changes, and the old address is told, so a stolen session cannot quietly move the
 * account (and its password resets) to an attacker's inbox.
 */
@Service
public class EmailChangeService {

    private static final Logger log = LoggerFactory.getLogger(EmailChangeService.class);

    private final UserRepository users;
    private final EmailTokenRepository tokens;
    private final NotificationChannelRepository channels;
    private final PasswordEncoder passwordEncoder;
    private final EmailSendThrottle throttle;
    private final AccountEmailProperties properties;
    private final ApplicationEventPublisher events;

    public EmailChangeService(UserRepository users, EmailTokenRepository tokens, NotificationChannelRepository channels,
                              PasswordEncoder passwordEncoder, EmailSendThrottle throttle,
                              AccountEmailProperties properties, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.channels = channels;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public void requestChange(Long userId, String rawNewEmail, String currentPassword, String clientIp) {
        User user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        String newEmail = UserMapper.normalizeEmail(rawNewEmail);
        if (newEmail.equals(user.getEmail())) {
            throw new InvalidMonitorException("newEmail", "That's already your email");
        }
        if (users.existsByEmail(newEmail)) {
            throw new EmailAlreadyUsedException(newEmail);
        }
        if (!throttle.tryAcquire("change", newEmail, clientIp)) {
            throw new TooManyAttemptsException("Too many email change requests. Try again later.");
        }
        Instant now = Instant.now();
        tokens.deleteExpired(now);
        // One pending change at a time: a new request retires the previous link.
        tokens.deleteAllForUser(userId, EmailTokenPurpose.CHANGE);

        String token = SecureTokens.newToken();
        EmailToken row = new EmailToken();
        row.setUser(user);
        row.setPurpose(EmailTokenPurpose.CHANGE);
        row.setEmail(newEmail);
        row.setTokenHash(SecureTokens.hash(token));
        row.setExpiresAt(now.plus(properties.verifyTokenTtl()));
        tokens.save(row);

        events.publishEvent(new AccountEmailEvents.EmailChangeRequested(
                newEmail, user.getEmail(), user.getName(), token, row.getExpiresAt()));
        log.info("Email change requested for user {}", userId);
    }

    /**
     * Switches the account to the confirmed address, which counts as verified (the link proved
     * it). The email alert channel that pointed at the old address follows it.
     */
    @Transactional
    public void confirm(String token) {
        EmailToken row = tokens.findForUpdate(SecureTokens.hash(token), EmailTokenPurpose.CHANGE)
                .orElseThrow(InvalidEmailTokenException::new);
        User user = row.getUser();
        tokens.deleteAllForUser(user.getId(), EmailTokenPurpose.CHANGE);
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidEmailTokenException();
        }
        String oldEmail = user.getEmail();
        String newEmail = row.getEmail();
        // Taken since the request? Checked again, and the unique index settles a race.
        if (users.existsByEmail(newEmail)) {
            throw new EmailAlreadyUsedException(newEmail);
        }
        user.setEmail(newEmail);
        user.setEmailVerifiedAt(Instant.now());
        // Any verification link for the old address is now meaningless.
        tokens.deleteAllForUser(user.getId(), EmailTokenPurpose.VERIFY);
        for (NotificationChannel channel : channels.findAllByUserId(user.getId())) {
            if (channel.getType() == ChannelType.EMAIL && channel.getTarget().equals(oldEmail)) {
                channel.setTarget(newEmail);
            }
        }
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyUsedException(newEmail);
        }
        events.publishEvent(new AccountEmailEvents.EmailChanged(user.getId(), user.getStripeCustomerId(), newEmail));
        log.info("Email changed for user {}", user.getId());
    }
}
