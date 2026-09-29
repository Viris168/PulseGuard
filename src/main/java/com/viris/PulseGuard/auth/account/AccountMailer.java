package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.common.config.AppProperties;
import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

/** Verification and email-change emails. After commit and async, like the reset email. */
@Component
public class AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties mail;
    private final AppProperties app;

    public AccountMailer(JavaMailSender mailSender, NotificationProperties mail, AppProperties app) {
        this.mailSender = mailSender;
        this.mail = mail;
        this.app = app;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationRequested(AccountEmailEvents.VerificationRequested event) {
        send(event.email(), "Verify your email for PulseGuard", """
                Hi %s,

                Confirm this is your address so PulseGuard can email you when a monitor goes down:
                %s

                The link works once and expires in %s. If you didn't create a PulseGuard account,
                ignore this email.
                """.formatted(event.name(), app.url("/verify-email?token=" + event.token()), expiresIn(event.expiresAt())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChangeRequested(AccountEmailEvents.EmailChangeRequested event) {
        send(event.newEmail(), "Confirm your new email for PulseGuard", """
                Hi %s,

                Confirm you want to use this address for your PulseGuard account:
                %s

                The link works once and expires in %s. Until then, nothing changes.
                """.formatted(event.name(), app.url("/confirm-email?token=" + event.token()), expiresIn(event.expiresAt())));
        // The old address hears about it too: if this wasn't the owner, it is their warning.
        send(event.oldEmail(), "Your PulseGuard email is being changed", """
                Hi %s,

                Someone signed in to your PulseGuard account asked to change its email to %s.
                Nothing changes until that address confirms.

                If this wasn't you, reset your password now: %s
                """.formatted(event.name(), event.newEmail(), app.url("/forgot-password")));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChannelConfirmationRequested(AccountEmailEvents.ChannelConfirmationRequested event) {
        send(event.email(), "Confirm PulseGuard alerts to this address", """
                Hello,

                %s added this address to receive PulseGuard alerts when their websites or APIs go
                down. If you want those alerts, confirm here:
                %s

                The link expires in %s. If you don't know them, ignore this email: you won't get
                any alerts unless you confirm.
                """.formatted(event.ownerName(), app.url("/confirm-channel?token=" + event.token()), expiresIn(event.expiresAt())));
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mail.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("Sent account email: {}", subject);
        } catch (Exception e) {
            // Never the recipient with the link, nor the exception text, which can quote the message.
            log.error("Account email failed ({}): {}", subject, e.getClass().getSimpleName());
        }
    }

    private static String expiresIn(Instant expiresAt) {
        long minutes = Math.max(1, Duration.between(Instant.now(), expiresAt).toMinutes());
        return minutes >= 120 ? Math.round(minutes / 60.0) + " hours" : minutes + " minutes";
    }
}
