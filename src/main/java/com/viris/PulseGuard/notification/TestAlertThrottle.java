package com.viris.PulseGuard.notification;

/**
 * Caps "send test alert" per user. Without it, anyone could add strangers' addresses as
 * channels and use the test button to send them mail from our domain, which would burn
 * the sending reputation real alerts depend on.
 * <p>
 * If the shared store is down, implementations keep counting per node (LocalFixedWindow):
 * a Redis outage must neither break the feature nor lift the limit.
 */
public interface TestAlertThrottle {

    /** @return true when this user may send another test alert now. */
    boolean tryAcquire(Long userId);
}
