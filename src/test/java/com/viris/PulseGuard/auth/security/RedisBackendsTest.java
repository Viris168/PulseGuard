package com.viris.PulseGuard.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Redis implementations themselves — the test doubles only prove the interface.
 * Uses a plain GenericContainer so no extra Testcontainers module is needed.
 */
class RedisBackendsTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StringRedisTemplate redis;

    static {
        REDIS.start();
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    RedisTokenDenylist denylist;
    RedisLoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        denylist = new RedisTokenDenylist(redis);
        rateLimiter = new RedisLoginRateLimiter(redis, 5, 20, Duration.ofMinutes(15));
    }

    // --- denylist ---------------------------------------------------------

    @Test
    void revokesAndReportsATokenId() {
        denylist.revoke("jti-1", Instant.now().plusSeconds(60));

        assertThat(denylist.isRevoked("jti-1")).isTrue();
        assertThat(denylist.isRevoked("jti-2")).isFalse();
    }

    @Test
    void setsATtlSoTheDenylistSelfPrunes() {
        denylist.revoke("jti-ttl", Instant.now().plusSeconds(60));

        Long ttl = redis.getExpire("jwt:revoked:jti-ttl");
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void ignoresAnAlreadyExpiredToken() {
        denylist.revoke("jti-old", Instant.now().minusSeconds(60));

        assertThat(denylist.isRevoked("jti-old")).isFalse();
    }

    @Test
    void revokesEverySessionIssuedBeforeTheCutoff() {
        Instant cutoff = Instant.now();
        denylist.revokeAllForUser(1L, cutoff, Duration.ofDays(30));

        assertThat(denylist.isRevokedForUser(1L, cutoff.minusSeconds(60))).isTrue();
        assertThat(denylist.isRevokedForUser(1L, cutoff.plusSeconds(60))).isFalse();
        assertThat(denylist.isRevokedForUser(2L, cutoff.minusSeconds(60))).isFalse();
    }

    @Test
    void keepsATokenMintedInTheSameSecondAsTheCutoff() {
        Instant now = Instant.now();
        denylist.revokeAllForUser(1L, now, Duration.ofDays(30));

        // A fresh login right after a logout must not be revoked by its own predecessor.
        assertThat(denylist.isRevokedForUser(1L, now)).isFalse();
    }

    // --- rate limiter -----------------------------------------------------

    @Test
    void allowsUpToTheLimitThenBlocks() {
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire("a@example.com", "10.0.0.1")).isTrue();
        }
        assertThat(rateLimiter.tryAcquire("a@example.com", "10.0.0.1")).isFalse();
    }

    @Test
    void scopesTheLimitToTheSourceAddress() {
        for (int i = 0; i < 6; i++) {
            rateLimiter.tryAcquire("victim@example.com", "10.0.0.66");
        }

        // The victim can still log in from their own address: no lockout-by-proxy.
        assertThat(rateLimiter.tryAcquire("victim@example.com", "10.0.0.1")).isTrue();
    }

    @Test
    void capsTotalAttemptsFromOneAddressAcrossAccounts() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.tryAcquire("user" + i + "@example.com", "10.0.0.99");
        }

        // Spraying many accounts from one host exhausts the IP budget.
        assertThat(rateLimiter.tryAcquire("fresh@example.com", "10.0.0.99")).isFalse();
    }

    @Test
    void resetClearsThePairButNotTheAddressBudget() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("a@example.com", "10.0.0.1");
        }
        rateLimiter.reset("a@example.com", "10.0.0.1");

        assertThat(redis.hasKey("login:attempts:10.0.0.1:a@example.com")).isFalse();
        assertThat(redis.hasKey("login:ip:10.0.0.1")).isTrue();
    }

    @Test
    void expiresTheWindow() {
        rateLimiter.tryAcquire("a@example.com", "10.0.0.1");

        assertThat(redis.getExpire("login:attempts:10.0.0.1:a@example.com"))
                .isBetween(1L, Duration.ofMinutes(15).toSeconds());
    }
}
