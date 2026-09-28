package com.viris.PulseGuard.auth.reset;

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

/**
 * Sends the reset link. After commit, so a rolled-back request never emails a dead link;
 * async, so the request takes the same time whether or not the address has an account.
 */
@Component
public class PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties mail;
    private final AppProperties app;

    public PasswordResetMailer(JavaMailSender mailSender, NotificationProperties mail, AppProperties app) {
        this.mailSender = mailSender;
        this.mail = mail;
        this.app = app;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResetRequested(PasswordResetRequestedEvent event) {
        long minutes = Math.max(1, Duration.between(Instant.now(), event.expiresAt()).toMinutes());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mail.from());
        message.setTo(event.email());
        message.setSubject("Reset your PulseGuard password");
        message.setText("""
                Hi %s,

                Someone (hopefully you) asked to reset the password for your PulseGuard account.

                Set a new password:
                %s

                This link works once and expires in %d minutes. If you didn't ask for this,
                ignore this email: your password stays the same.
                """.formatted(event.name(), app.url("/reset-password?token=" + event.token()), minutes));
        try {
            mailSender.send(message);
            log.info("Sent password reset email");
        } catch (Exception e) {
            // Never the address with the link, nor the exception text, which can quote the message.
            log.error("Password reset email failed: {}", e.getClass().getSimpleName());
        }
    }
}
