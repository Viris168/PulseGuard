package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.common.ratelimit.LocalFixedWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** One counter per user per UTC day, shared by every node through Redis. */
@Slf4j
@Component
public class RedisAiQuestionQuota implements AiQuestionQuota {

    private static final String PREFIX = "ai:questions:";
    /** Longer than a day, so a counter outlives its date and then expires on its own. */
    private static final Duration KEEP = Duration.ofHours(26);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final LocalFixedWindow fallback = new LocalFixedWindow();

    @Autowired
    public RedisAiQuestionQuota(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    RedisAiQuestionQuota(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long used(Long userId) {
        String key = key(userId);
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Math.max(0, Long.parseLong(value));
        } catch (DataAccessException ex) {
            log.warn("Rate-limit store unavailable; reading Ask AI usage locally for userId={}", userId);
            return fallback.count(key);
        }
    }

    @Override
    public boolean tryAcquire(Long userId, int limit) {
        String key = key(userId);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, KEEP);
            }
            if (count != null && count > limit) {
                redis.opsForValue().decrement(key); // refused questions don't count as used
                return false;
            }
            return true;
        } catch (DataAccessException ex) {
            log.warn("Rate-limit store unavailable; counting Ask AI questions locally for userId={}", userId);
            if (fallback.count(key) >= limit) {
                return false;
            }
            fallback.increment(key, KEEP);
            return true;
        }
    }

    @Override
    public void release(Long userId) {
        try {
            Long count = redis.opsForValue().decrement(key(userId));
            if (count != null && count < 0) {
                redis.delete(key(userId));
            }
        } catch (DataAccessException ex) {
            // The local fallback can't count down; the user loses one question until tomorrow.
            log.warn("Rate-limit store unavailable; Ask AI question not handed back for userId={}", userId);
        }
    }

    private String key(Long userId) {
        return PREFIX + userId + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
