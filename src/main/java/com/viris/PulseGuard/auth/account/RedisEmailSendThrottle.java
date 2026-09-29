package com.viris.PulseGuard.auth.account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.viris.PulseGuard.common.ratelimit.LocalFixedWindow;
import org.springframework.stereotype.Component;

/** Fixed windows in Redis: one budget per flow and address, a wider shared one per IP. */
@Slf4j
@Component
public class RedisEmailSendThrottle implements EmailSendThrottle {

    private static final String PREFIX = "account-email:";

    private final StringRedisTemplate redis;
    private final AccountEmailProperties properties;
    private final LocalFixedWindow fallback = new LocalFixedWindow();

    public RedisEmailSendThrottle(StringRedisTemplate redis, AccountEmailProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String purpose, String email, String clientIp) {
        try {
            boolean emailAllowed = increment(PREFIX + purpose + ":" + email) <= properties.maxPerEmail();
            boolean ipAllowed = increment(PREFIX + "ip:" + clientIp) <= properties.maxPerIp();
            return emailAllowed && ipAllowed;
        } catch (DataAccessException ex) {
            log.warn("Rate-limit store unavailable; counting {} emails locally", purpose);
            boolean emailAllowed = fallback.increment(PREFIX + purpose + ":" + email, properties.window()) <= properties.maxPerEmail();
            boolean ipAllowed = fallback.increment(PREFIX + "ip:" + clientIp, properties.window()) <= properties.maxPerIp();
            return emailAllowed && ipAllowed;
        }
    }

    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, properties.window());
        }
        return count == null ? 1L : count;
    }
}
