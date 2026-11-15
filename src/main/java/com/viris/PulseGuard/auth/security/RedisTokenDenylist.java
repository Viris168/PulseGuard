package com.viris.PulseGuard.auth.security;

import com.viris.PulseGuard.common.exception.RevocationUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Entries expire with the token itself, so the denylist never grows beyond the tokens
 * currently in flight.
 * <p>
 * Every read fails open (see {@link TokenDenylist}): if Redis is unreachable, a revoked token
 * keeps working until it expires rather than every request failing. Writes that fail are logged
 * loudly, because a revocation that silently did not happen is worth alerting on.
 */
@Slf4j
@Component
public class RedisTokenDenylist implements TokenDenylist {

    private static final String JTI_PREFIX = "jwt:revoked:";
    private static final String EPOCH_PREFIX = "jwt:epoch:";

    private final StringRedisTemplate redis;

    public RedisTokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // Already expired; nothing to revoke.
        }
        try {
            redis.opsForValue().set(JTI_PREFIX + jti, "1", ttl);
        } catch (DataAccessException ex) {
            log.error("Could not revoke token: revocation store unavailable", ex);
        }
    }

    @Override
    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(JTI_PREFIX + jti));
        } catch (DataAccessException ex) {
            log.warn("Revocation store unavailable; allowing token until it expires");
            return false;
        }
    }

    @Override
    public void revokeAllForUser(Long userId, Instant cutoff, Duration retention) {
        try {
            redis.opsForValue().set(EPOCH_PREFIX + userId, String.valueOf(cutoff.toEpochMilli()), retention);
        } catch (DataAccessException ex) {
            log.error("Could not revoke sessions for user {}: store unavailable", userId, ex);
            throw new RevocationUnavailableException(ex);
        }
    }

    @Override
    public boolean isRevokedForUser(Long userId, Instant sessionStartedAt) {
        try {
            String stored = redis.opsForValue().get(EPOCH_PREFIX + userId);
            if (stored == null) {
                return false;
            }
            // Strictly before: the fresh pair a password change hands back starts at or after its
            // own cut-off and is kept. An older session is revoked however recently it refreshed,
            // because a refresh keeps the session's start.
            return sessionStartedAt.toEpochMilli() < cutoffMillis(Long.parseLong(stored));
        } catch (DataAccessException | NumberFormatException ex) {
            log.warn("Revocation store unavailable; allowing token until it expires");
            return false;
        }
    }

    /**
     * Cut-offs written before millisecond precision hold epoch seconds; they stay valid for one
     * session retention after the upgrade. Epoch seconds stay below 10^11 until the year 5138.
     */
    static long cutoffMillis(long stored) {
        return stored < 100_000_000_000L ? stored * 1000 : stored;
    }
}
