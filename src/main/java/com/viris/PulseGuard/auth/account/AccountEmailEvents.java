package com.viris.PulseGuard.auth.account;

import java.time.Instant;

/** Published inside a transaction; the emails go out after commit (AccountMailer). */
public final class AccountEmailEvents {

    private AccountEmailEvents() {
    }

    public record VerificationRequested(String email, String name, String token, Instant expiresAt) {
        /** Never print the token: it is a working credential until it expires. */
        @Override
        public String toString() {
            return "VerificationRequested[email=" + email + "]";
        }
    }

    public record EmailChangeRequested(String newEmail, String oldEmail, String name, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "EmailChangeRequested";
        }
    }

    /** Someone added {@code email} as an alert channel; it has to confirm before alerts go there. */
    public record ChannelConfirmationRequested(String email, String ownerName, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "ChannelConfirmationRequested";
        }
    }

    /** After an email change commits; billing updates the Stripe customer from it. */
    public record EmailChanged(Long userId, String stripeCustomerId, String newEmail) {
    }
}
