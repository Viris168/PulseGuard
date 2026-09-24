package com.viris.PulseGuard.scheduling;

/**
 * Unchecked wrapper for Quartz's checked {@code SchedulerException}. Unchecked on purpose:
 * thrown inside a {@code @Transactional} monitor change, it rolls the monitor row back
 * together with the half-written schedule.
 */
public class CheckSchedulingException extends RuntimeException {

    public CheckSchedulingException(String message, Throwable cause) {
        super(message, cause);
    }
}
