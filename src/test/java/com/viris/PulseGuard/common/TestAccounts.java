package com.viris.PulseGuard.common;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Helpers for tests that register accounts. Sign-up sends a verification email after commit,
 * on another thread, and email alerts wait until the address is verified; a test about alerts
 * wants neither in its way.
 */
public final class TestAccounts {

    public static final String VERIFY_SUBJECT = "Verify your email for PulseGuard";

    private TestAccounts() {
    }

    /**
     * Waits for sign-up's verification email to {@code email} to be sent, then forgets every
     * send so far. Without the wait, the email could land after a test's own count began.
     */
    public static void awaitVerificationEmail(JavaMailSender mailSender, String email) {
        verify(mailSender, timeout(5000)).send(argThat((SimpleMailMessage m) -> m != null
                && VERIFY_SUBJECT.equals(m.getSubject())
                && m.getTo() != null && Arrays.asList(m.getTo()).contains(email)));
        clearInvocations(mailSender);
    }

    /** Marks the account verified, as clicking the emailed link would. */
    public static User markVerified(UserRepository users, String email) {
        User user = users.findByEmail(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        return users.save(user);
    }
}
