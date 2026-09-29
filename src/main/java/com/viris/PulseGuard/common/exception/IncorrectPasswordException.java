package com.viris.PulseGuard.common.exception;

/**
 * The signed-in user typed the wrong current password. A 400 on the field, not a 401: the
 * frontend reads any 401 as "session expired" and signs the user out.
 */
public class IncorrectPasswordException extends RuntimeException {
    public IncorrectPasswordException() {
        super("Current password is incorrect");
    }
}
