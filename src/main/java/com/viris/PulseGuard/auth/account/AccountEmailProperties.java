package com.viris.PulseGuard.auth.account;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Emails the account sends itself: password reset, email verification, email change.
 *
 * @param resetTokenTtl  how long a "forgot password" link works
 * @param verifyTokenTtl how long a verification or email-change link works
 * @param maxPerEmail    emails of one kind one address can be sent per window
 * @param maxPerIp       such requests one IP can make per window, across addresses and kinds
 * @param window         the rate-limit window
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.account-email")
public record AccountEmailProperties(
        @NotNull Duration resetTokenTtl,
        @NotNull Duration verifyTokenTtl,
        @Min(1) int maxPerEmail,
        @Min(1) int maxPerIp,
        @NotNull Duration window
) {
}
