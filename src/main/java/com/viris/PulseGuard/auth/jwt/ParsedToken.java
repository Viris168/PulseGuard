package com.viris.PulseGuard.auth.jwt;

import java.time.Instant;

/**
 * A verified token. Carries identity only — never authority or plan, which are read from the
 * database so a demotion or downgrade takes effect immediately.
 */
public record ParsedToken(
        Long userId,
        String email,
        String jti,
        TokenType type,
        Instant issuedAt,
        Instant expiresAt,
        /** The sign-in this token descends from; a refresh keeps it, so revoke-all can end the chain. */
        Instant sessionStartedAt
) {
}
