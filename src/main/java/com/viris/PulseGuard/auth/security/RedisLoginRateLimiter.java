package com.viris.PulseGuard.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.viris.PulseGuard.common.ratelimit.LocalFixedWindow;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed window, two budgets: one per (IP, email) pair and a wider one per IP.
 */
@Slf4j
@Component
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final String PAIR_PREFIX = "login:attempts:";
    private static final String IP_PREFIX = "login:ip:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final int maxAttemptsPerIp;
    private final Duration window;
    private final LocalFixedWindow fallback = new LocalFixedWindow();

    public RedisLoginRateLimiter(StringRedisTemplate redis,
                                 @Value("${pulseguard.login.max-attempts}") int maxAttempts,
                                 @Value("${pulseguard.login.max-attempts-per-ip}") int maxAttemptsPerIp,
                                 @Value("${pulseguard.login.window}") Duration window) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
        this.window = window;
    }

    @Override
    public boolean tryAcquire(String email, String clientIp) {
        try {
            boolean pairAllowed = increment(PAIR_PREFIX + clientIp + ":" + email) <= maxAttempts;
            boolean ipAllowed = increment(IP_PREFIX + clientIp) <= maxAttemptsPerIp;
            return pairAllowed && ipAllowed;
        } catch (DataAccessException ex) {
            // Not fail-open: counted in this node's memory until Redis is back.
            log.warn("Rate-limit store unavailable; counting login attempts locally");
            boolean pairAllowed = fallback.increment(PAIR_PREFIX + clientIp + ":" + email, window) <= maxAttempts;
            boolean ipAllowed = fallback.increment(IP_PREFIX + clientIp, window) <= maxAttemptsPerIp;
            return pairAllowed && ipAllowed;
        }
    }

    @Override
    public void reset(String email, String clientIp) {
        try {
            // Only the pair counter: succeeding on one account must not refill the IP budget.
            redis.delete(PAIR_PREFIX + clientIp + ":" + email);
            fallback.reset(PAIR_PREFIX + clientIp + ":" + email);
        } catch (DataAccessException ex) {
            fallback.reset(PAIR_PREFIX + clientIp + ":" + email);
            log.warn("Rate-limit store unavailable; attempt counter not cleared");
        }
    }

    private long increment(String key) {
        Long attempts = redis.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redis.expire(key, window); // Start the window on the first attempt.
        }
        return attempts == null ? 1L : attempts;
    }
}
