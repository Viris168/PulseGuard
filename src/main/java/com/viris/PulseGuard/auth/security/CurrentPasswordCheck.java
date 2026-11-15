package com.viris.PulseGuard.auth.security;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.common.exception.IncorrectPasswordException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The "enter your current password" step of password change, email change and account deletion.
 * Throttled per user like a login, so a stolen session cannot guess the password that would let
 * it take the account over.
 */
@Component
public class CurrentPasswordCheck {

    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public CurrentPasswordCheck(PasswordEncoder passwordEncoder, LoginRateLimiter rateLimiter) {
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    /**
     * @throws TooManyAttemptsException  after too many wrong passwords in the login window.
     * @throws IncorrectPasswordException when {@code rawPassword} is not the user's password.
     */
    public void verify(User user, String rawPassword) {
        if (!rateLimiter.tryAcquireForUser(user.getId())) {
            throw new TooManyAttemptsException("Too many wrong passwords. Try again later.");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        rateLimiter.resetForUser(user.getId());
    }
}
