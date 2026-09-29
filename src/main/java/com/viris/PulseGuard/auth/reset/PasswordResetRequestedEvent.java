package com.viris.PulseGuard.auth.reset;

import java.time.Instant;

/** Published inside the request's transaction; the email goes out after commit. */
public record PasswordResetRequestedEvent(String email, String name, String token, Instant expiresAt) {

    /** Never print the token: it is a working credential until it expires. */
    @Override
    public String toString() {
        return "PasswordResetRequestedEvent[email=" + email + "]";
    }
}
