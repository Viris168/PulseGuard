package com.viris.PulseGuard.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counters in this node's memory: what the Redis rate limiters fall back to when
 * Redis is down. Limits then hold per node instead of across the cluster, which is looser but
 * never unlimited, where failing open would switch throttling off entirely during an outage.
 */
public final class LocalFixedWindow {

    /** Bounds memory during a long outage; expired entries are dropped first. */
    static final int MAX_KEYS = 100_000;

    private record Window(long count, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocalFixedWindow() {
        this(Clock.systemUTC());
    }

    LocalFixedWindow(Clock clock) {
        this.clock = clock;
    }

    /** Counts one more event for {@code key}; the window starts at the first one, as in Redis. */
    public long increment(String key, Duration window) {
        Instant now = clock.instant();
        if (windows.size() >= MAX_KEYS) {
            windows.values().removeIf(w -> !w.expiresAt().isAfter(now));
            if (windows.size() >= MAX_KEYS) {
                windows.clear(); // still full: an attack on the fallback itself; start over
            }
        }
        return windows.compute(key, (k, w) -> w == null || !w.expiresAt().isAfter(now)
                ? new Window(1, now.plus(window))
                : new Window(w.count() + 1, w.expiresAt())).count();
    }

    /** Events counted so far in {@code key}'s current window, without counting one. */
    public long count(String key) {
        Window w = windows.get(key);
        return w == null || !w.expiresAt().isAfter(clock.instant()) ? 0 : w.count();
    }

    public void reset(String key) {
        windows.remove(key);
    }
}
