package com.viris.PulseGuard.auth.security;

/**
 * Throttles login attempts.
 * <p>
 * Counters are scoped by client IP as well as email. Keying on email alone would let anyone
 * lock a known account out of its own login by failing five times from anywhere; scoping to the
 * source means an attacker only ever throttles themselves. A separate per-IP budget stops one
 * host spraying many accounts.
 * <p>
 * If the backing store is unreachable, implementations must neither reject every login nor
 * stop limiting: they count per node in memory (LocalFixedWindow) until the store is back.
 */
public interface LoginRateLimiter {

    /** @return true when this email/IP pair still has attempts left. */
    boolean tryAcquire(String email, String clientIp);

    /** Called after a successful login so a legitimate user is not punished for earlier typos. */
    void reset(String email, String clientIp);
}
