package com.viris.PulseGuard.auth.security;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link RedisTokenDenylist}; same contract, no Redis. */
public class InMemoryTokenDenylist implements TokenDenylist {

    private final Map<String, Instant> revokedJtis = new ConcurrentHashMap<>();
    private final Map<Long, Long> userCutoffs = new ConcurrentHashMap<>();

    @Override
    public void revoke(String jti, Instant expiresAt) {
        if (expiresAt.isAfter(Instant.now())) {
            revokedJtis.put(jti, expiresAt);
        }
    }

    @Override
    public boolean isRevoked(String jti) {
        Instant expiry = revokedJtis.get(jti);
        return expiry != null && expiry.isAfter(Instant.now());
    }

    @Override
    public void revokeAllForUser(Long userId, Instant issuedBefore, Duration retention) {
        userCutoffs.put(userId, issuedBefore.truncatedTo(ChronoUnit.SECONDS).getEpochSecond());
    }

    @Override
    public boolean isRevokedForUser(Long userId, Instant tokenIssuedAt) {
        Long cutoff = userCutoffs.get(userId);
        return cutoff != null && tokenIssuedAt.getEpochSecond() < cutoff;
    }
}
