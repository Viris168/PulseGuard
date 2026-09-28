package com.viris.PulseGuard.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFixedWindowTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final LocalFixedWindow counter = new LocalFixedWindow(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    @Test
    void countsWithinAWindowAndStartsAgainAfterIt() {
        Duration window = Duration.ofMinutes(15);
        assertThat(counter.increment("k", window)).isEqualTo(1);
        assertThat(counter.increment("k", window)).isEqualTo(2);

        now.set(now.get().plus(window));

        assertThat(counter.increment("k", window)).isEqualTo(1);
    }

    @Test
    void keysAreIndependentAndResetClearsOne() {
        counter.increment("a", Duration.ofMinutes(1));
        counter.increment("a", Duration.ofMinutes(1));
        assertThat(counter.increment("b", Duration.ofMinutes(1))).isEqualTo(1);

        counter.reset("a");

        assertThat(counter.increment("a", Duration.ofMinutes(1))).isEqualTo(1);
    }
}
