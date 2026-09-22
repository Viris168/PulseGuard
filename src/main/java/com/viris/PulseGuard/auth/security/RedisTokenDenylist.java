package com.viris.PulseGuard.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
    public void revokeAllForUser(Long userId, Instant issuedBefore, Duration retention) {
        try {
            // Second precision, matching the token's own iat claim.
            String cutoff = String.valueOf(issuedBefore.truncatedTo(ChronoUnit.SECONDS).getEpochSecond());
            redis.opsForValue().set(EPOCH_PREFIX + userId, cutoff, retention);
        } catch (DataAccessException ex) {
            log.error("Could not revoke sessions for user {}: store unavailable", userId, ex);
        }
    }

    @Override
    public boolean isRevokedForUser(Long userId, Instant tokenIssuedAt) {
        try {
            String cutoff = redis.opsForValue().get(EPOCH_PREFIX + userId);
            if (cutoff == null) {
                return false;
            }
            // Strictly before: a token minted in the same second as the cut-off is kept, so a
            // fresh login immediately after a logout is not revoked by its own predecessor.
            return tokenIssuedAt.getEpochSecond() < Long.parseLong(cutoff);
        } catch (DataAccessException | NumberFormatException ex) {
            log.warn("Revocation store unavailable; allowing token until it expires");
            return false;
        }
    }
}
