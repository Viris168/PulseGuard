package com.viris.PulseGuard.auth.security;

import java.time.Duration;
import java.time.Instant;

/**
 * Revoked tokens, by individual id and by user cut-off. Kept behind an interface so Redis
 * stays swappable.
 * <p>
 * Implementations must <em>fail open</em>: when the backing store is unreachable, report
 * "not revoked" rather than throwing, so an outage of the revocation store does not take
 * authentication down with it. Access tokens are short-lived, which bounds the exposure.
 */
public interface TokenDenylist {

    /** Revokes one token id until it would have expired anyway. */
    void revoke(String jti, Instant expiresAt);

    boolean isRevoked(String jti);

    /**
     * Revokes every token for a user issued before {@code issuedBefore} — the only way to end
     * sessions whose tokens the client never hands back (and what a password change should call).
     *
     * @param retention how long to remember the cut-off; must outlive the longest refresh token.
     */
    void revokeAllForUser(Long userId, Instant issuedBefore, Duration retention);

    boolean isRevokedForUser(Long userId, Instant tokenIssuedAt);
}
