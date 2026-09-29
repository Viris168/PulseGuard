package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.account.AccountEmailEvents;
import com.viris.PulseGuard.auth.account.AccountEmailProperties;
import com.viris.PulseGuard.auth.account.EmailSendThrottle;
import com.viris.PulseGuard.auth.account.EmailToken;
import com.viris.PulseGuard.auth.account.EmailTokenRepository;
import com.viris.PulseGuard.auth.account.SecureTokens;
import com.viris.PulseGuard.common.exception.InvalidEmailTokenException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.EmailTokenPurpose;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Confirms that an address added as an email alert channel wants the alerts. Until it does,
 * nothing is sent there (NotificationService, ChannelService#sendTestAlert).
 */
@Service
public class ChannelConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfirmationService.class);

    private final EmailTokenRepository tokens;
    private final NotificationChannelRepository channels;
    private final EmailSendThrottle throttle;
    private final AccountEmailProperties properties;
    private final ApplicationEventPublisher events;

    public ChannelConfirmationService(EmailTokenRepository tokens, NotificationChannelRepository channels,
                                      EmailSendThrottle throttle, AccountEmailProperties properties,
                                      ApplicationEventPublisher events) {
        this.tokens = tokens;
        this.channels = channels;
        this.throttle = throttle;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Emails {@code address} a confirmation link. Throttled per address, and per account (in
     * place of an IP), so the channel form cannot be used to mail-bomb anyone.
     */
    @Transactional
    public void request(User owner, String address) {
        if (!throttle.tryAcquire("channel", address, "user:" + owner.getId())) {
            throw new TooManyAttemptsException("Too many confirmation emails. Try again later.");
        }
        tokens.deleteForAddress(owner.getId(), EmailTokenPurpose.CHANNEL, address);
        String token = SecureTokens.newToken();
        EmailToken row = new EmailToken();
        row.setUser(owner);
        row.setPurpose(EmailTokenPurpose.CHANNEL);
        row.setEmail(address);
        row.setTokenHash(SecureTokens.hash(token));
        row.setExpiresAt(Instant.now().plus(properties.verifyTokenTtl()));
        tokens.save(row);
        events.publishEvent(new AccountEmailEvents.ChannelConfirmationRequested(
                address, owner.getName(), token, row.getExpiresAt()));
    }

    /** From the emailed link; works for anyone holding it, signed in or not. */
    @Transactional
    public void confirm(String token) {
        EmailToken row = tokens.findForUpdate(SecureTokens.hash(token), EmailTokenPurpose.CHANNEL)
                .orElseThrow(InvalidEmailTokenException::new);
        Long userId = row.getUser().getId();
        tokens.deleteForAddress(userId, EmailTokenPurpose.CHANNEL, row.getEmail());
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidEmailTokenException();
        }
        Instant now = Instant.now();
        int confirmed = 0;
        for (NotificationChannel channel : channels.findAllByUserId(userId)) {
            if (channel.getType() == ChannelType.EMAIL && channel.getTarget().equals(row.getEmail())
                    && channel.getVerifiedAt() == null) {
                channel.setVerifiedAt(now);
                confirmed++;
            }
        }
        if (confirmed == 0) {
            // The channel was deleted after the link was sent: nothing left to confirm.
            throw new InvalidEmailTokenException();
        }
        log.info("Email channel confirmed for user {}", userId);
    }
}
