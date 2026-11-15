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

    /** A full scan for expired windows runs at most this often, so a flood can't make each call pay for one. */
    static final Duration PURGE_INTERVAL = Duration.ofSeconds(1);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile Instant lastPurge = Instant.MIN;

    public LocalFixedWindow() {
        this(Clock.systemUTC());
    }

    LocalFixedWindow(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one more event for {@code key}; the window starts at the first one, as in Redis.
     *
     * @return the count so far, or {@link Long#MAX_VALUE} (over any limit) for a new key while
     *         the table is full of live windows.
     */
    public long increment(String key, Duration window) {
        Instant now = clock.instant();
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            if (!now.isBefore(lastPurge.plus(PURGE_INTERVAL))) {
                lastPurge = now;
                windows.values().removeIf(w -> !w.expiresAt().isAfter(now));
            }
            if (windows.size() >= MAX_KEYS) {
                // Still full: someone is filling the fallback on purpose. Clearing it would wipe
                // the counters that are holding them back (a victim's login attempts included),
                // so the keys already counted keep counting and new ones are refused until
                // windows expire.
                return Long.MAX_VALUE;
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
