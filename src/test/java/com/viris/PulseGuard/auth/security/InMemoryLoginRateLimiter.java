package com.viris.PulseGuard.auth.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link RedisLoginRateLimiter}; fixed window, no Redis. */
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private final Map<String, Integer> pairAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> ipAttempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final int maxAttemptsPerIp;

    public InMemoryLoginRateLimiter(int maxAttempts, int maxAttemptsPerIp) {
        this.maxAttempts = maxAttempts;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
    }

    @Override
    public boolean tryAcquire(String email, String clientIp) {
        boolean pairAllowed = pairAttempts.merge(clientIp + ":" + email, 1, Integer::sum) <= maxAttempts;
        boolean ipAllowed = ipAttempts.merge(clientIp, 1, Integer::sum) <= maxAttemptsPerIp;
        return pairAllowed && ipAllowed;
    }

    @Override
    public void reset(String email, String clientIp) {
        pairAttempts.remove(clientIp + ":" + email);
    }
}
