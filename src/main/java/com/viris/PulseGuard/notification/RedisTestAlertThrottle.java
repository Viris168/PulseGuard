package com.viris.PulseGuard.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.viris.PulseGuard.common.ratelimit.LocalFixedWindow;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Fixed window per user, shared by every node through Redis. */
@Slf4j
@Component
public class RedisTestAlertThrottle implements TestAlertThrottle {

    private static final String PREFIX = "channel:test:";

    private final StringRedisTemplate redis;
    private final int maxTests;
    private final Duration window;
    private final LocalFixedWindow fallback = new LocalFixedWindow();

    public RedisTestAlertThrottle(StringRedisTemplate redis,
                                  @Value("${pulseguard.channels.max-tests:5}") int maxTests,
                                  @Value("${pulseguard.channels.test-window:10m}") Duration window) {
        this.redis = redis;
        this.maxTests = maxTests;
        this.window = window;
    }

    @Override
    public boolean tryAcquire(Long userId) {
        try {
            String key = PREFIX + userId;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window); // Start the window on the first test.
            }
            return count == null || count <= maxTests;
        } catch (DataAccessException ex) {
            log.warn("Rate-limit store unavailable; counting test alerts locally for userId={}", userId);
            return fallback.increment(PREFIX + userId, window) <= maxTests;
        }
    }
}
