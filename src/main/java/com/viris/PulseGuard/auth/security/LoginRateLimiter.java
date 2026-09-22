package com.viris.PulseGuard.auth.security;

/**
 * Throttles login attempts.
 * <p>
 * Counters are scoped by client IP as well as email. Keying on email alone would let anyone
 * lock a known account out of its own login by failing five times from anywhere; scoping to the
 * source means an attacker only ever throttles themselves. A separate per-IP budget stops one
 * host spraying many accounts.
 * <p>
 * Implementations must <em>fail open</em>: if the backing store is unreachable, allow the attempt
 * rather than rejecting every login. BCrypt still makes guessing expensive.
 */
public interface LoginRateLimiter {

    /** @return true when this email/IP pair still has attempts left. */
    boolean tryAcquire(String email, String clientIp);

    /** Called after a successful login so a legitimate user is not punished for earlier typos. */
    void reset(String email, String clientIp);
}
