package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.common.ratelimit.LocalFixedWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** One lease per incident at a time, shared by every node through Redis (SET NX with a TTL). */
@Slf4j
@Component
public class RedisSummaryLease implements SummaryLease {

    private static final String PREFIX = "ai:summary:lease:";

    private final StringRedisTemplate redis;
    private final Duration lease;
    private final LocalFixedWindow fallback = new LocalFixedWindow();

    public RedisSummaryLease(StringRedisTemplate redis,
                             @Value("${pulseguard.ai.summary-retry-after}") Duration lease) {
        this.redis = redis;
        this.lease = lease;
    }

    @Override
    public boolean tryAcquire(Long incidentId) {
        String key = PREFIX + incidentId;
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", lease));
        } catch (DataAccessException ex) {
            log.warn("Lease store unavailable; leasing summaries locally for incidentId={}", incidentId);
            return fallback.increment(key, lease) == 1;
        }
    }
}
