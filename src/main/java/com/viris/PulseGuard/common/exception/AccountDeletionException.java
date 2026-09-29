package com.viris.PulseGuard.common.exception;

/** Deleting stopped before anything was deleted, because the subscription could not be cancelled. */
public class AccountDeletionException extends RuntimeException {
    public AccountDeletionException() {
        super("We couldn't cancel your subscription with Stripe, so nothing was deleted. Try again in a minute.");
    }
}
