package com.viris.PulseGuard.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The Redis quota itself; the integration tests use {@link InMemoryAiQuestionQuota}. */
class RedisAiQuestionQuotaTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StringRedisTemplate redis;

    static {
        REDIS.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    private static final Clock MONDAY = Clock.fixed(Instant.parse("2026-09-28T23:59:00Z"), ZoneOffset.UTC);
    private static final Clock TUESDAY = Clock.fixed(Instant.parse("2026-09-29T00:01:00Z"), ZoneOffset.UTC);

    RedisAiQuestionQuota quota;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        quota = new RedisAiQuestionQuota(redis, MONDAY);
    }

    @Test
    void countsQuestionsUpToTheLimit() {
        assertThat(quota.tryAcquire(1L, 2)).isTrue();
        assertThat(quota.tryAcquire(1L, 2)).isTrue();
        assertThat(quota.used(1L)).isEqualTo(2);
    }

    @Test
    void refusesPastTheLimitWithoutCountingTheRefusal() {
        quota.tryAcquire(1L, 1);

        assertThat(quota.tryAcquire(1L, 1)).isFalse();
        assertThat(quota.tryAcquire(1L, 1)).isFalse();
        assertThat(quota.used(1L)).isEqualTo(1);
    }

    @Test
    void handsBackAQuestionThatGotNoAnswer() {
        quota.tryAcquire(1L, 1);
        quota.release(1L);

        assertThat(quota.used(1L)).isZero();
        assertThat(quota.tryAcquire(1L, 1)).isTrue();
    }

    @Test
    void neverGoesBelowZero() {
        quota.release(1L);

        assertThat(quota.used(1L)).isZero();
    }

    @Test
    void keepsEachUserSeparate() {
        quota.tryAcquire(1L, 1);

        assertThat(quota.tryAcquire(2L, 1)).isTrue();
        assertThat(quota.used(2L)).isEqualTo(1);
    }

    @Test
    void startsAgainOnANewUtcDayAndLetsOldCountersExpire() {
        quota.tryAcquire(1L, 1);

        RedisAiQuestionQuota tomorrow = new RedisAiQuestionQuota(redis, TUESDAY);

        assertThat(tomorrow.used(1L)).isZero();
        assertThat(tomorrow.tryAcquire(1L, 1)).isTrue();
        assertThat(redis.getExpire("ai:questions:1:2026-09-28")).isPositive();
    }
}
