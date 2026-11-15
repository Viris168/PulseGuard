package com.viris.PulseGuard.auth.security;

import com.viris.PulseGuard.common.exception.RevocationUnavailableException;

import java.time.Duration;
import java.time.Instant;

/**
 * Revoked tokens, by individual id and by user cut-off. Kept behind an interface so Redis
 * stays swappable.
 * <p>
 * Reads must <em>fail open</em>: when the backing store is unreachable, report "not revoked"
 * rather than throwing, so an outage of the revocation store does not take authentication down
 * with it. Access tokens are short-lived, which bounds the exposure.
 * <p>
 * {@link #revokeAllForUser} must <em>fail closed</em>: it throws
 * {@link RevocationUnavailableException}, because its callers (password change and reset, logout)
 * promise the user every other session is over, and must not report success when it isn't.
 */
public interface TokenDenylist {

    /** Revokes one token id until it would have expired anyway. */
    void revoke(String jti, Instant expiresAt);

    boolean isRevoked(String jti);

    /**
     * Revokes every session for a user that started before {@code cutoff} — the only way to end
     * sessions whose tokens the client never hands back (and what a password change should call).
     * Millisecond precision: a session that started in the same second, before the cut-off, ends too.
     *
     * @param retention how long to remember the cut-off; must outlive the longest refresh token.
     * @throws RevocationUnavailableException if the cut-off could not be stored.
     */
    void revokeAllForUser(Long userId, Instant cutoff, Duration retention);

    /** Whether a token whose session started at {@code sessionStartedAt} has been revoked. */
    boolean isRevokedForUser(Long userId, Instant sessionStartedAt);
}
