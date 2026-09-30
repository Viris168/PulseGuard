package com.viris.PulseGuard.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link RedisAiQuestionQuota}; one day that never ends, no Redis. */
public class InMemoryAiQuestionQuota implements AiQuestionQuota {

    private final Map<Long, Long> used = new ConcurrentHashMap<>();

    @Override
    public long used(Long userId) {
        return used.getOrDefault(userId, 0L);
    }

    @Override
    public synchronized boolean tryAcquire(Long userId, int limit) {
        if (used(userId) >= limit) {
            return false;
        }
        used.merge(userId, 1L, Long::sum);
        return true;
    }

    @Override
    public synchronized void release(Long userId) {
        used.computeIfPresent(userId, (id, n) -> n > 1 ? n - 1 : null);
    }

    public void clear() {
        used.clear();
    }
}
