package com.viris.PulseGuard.common.exception;

/**
 * Other sessions could not be signed out because the revocation store is unreachable. The
 * change that needed it (a new password, a logout) is rolled back rather than reported done.
 */
public class RevocationUnavailableException extends RuntimeException {
    public RevocationUnavailableException(Throwable cause) {
        super("We couldn't sign out your other sessions just now, so nothing was changed. Try again in a minute.", cause);
    }
}
