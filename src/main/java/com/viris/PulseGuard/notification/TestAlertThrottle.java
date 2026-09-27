package com.viris.PulseGuard.notification;

/**
 * Caps "send test alert" per user. Without it, anyone could add strangers' addresses as
 * channels and use the test button to send them mail from our domain, which would burn
 * the sending reputation real alerts depend on.
 * <p>
 * Implementations must fail open, like the login limiter: a Redis outage must not break
 * a feature that exists to check that alerts work.
 */
public interface TestAlertThrottle {

    /** @return true when this user may send another test alert now. */
    boolean tryAcquire(Long userId);
}
