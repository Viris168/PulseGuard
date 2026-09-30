package com.viris.PulseGuard.ai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * PulseGuard's own AI settings. The provider, model and key are Spring AI's
 * ({@code spring.ai.*}), so switching provider stays a configuration change.
 *
 * @param openSummaryTtl        how long an open incident's summary is served before it is rewritten
 *                              with the checks that came in since. A resolved incident's summary never expires.
 * @param timeout               the longest a request waits for the model, whichever provider is
 *                              configured: the whole answer for {@code call}, the first piece for {@code stream}.
 * @param streamTimeout         the longest a streamed answer may take from start to finish, so a
 *                              provider that trickles text forever can't hold a connection open.
 * @param fairUseDailyQuestions the Ask AI cap for plans sold as unlimited: every question costs
 *                              money, so "unlimited" still stops a script from running up the bill.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.ai")
public record AiProperties(@NotNull Duration openSummaryTtl,
                           @NotNull Duration timeout,
                           @NotNull Duration streamTimeout,
                           @Min(1) int fairUseDailyQuestions) {
}
