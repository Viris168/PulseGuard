package com.viris.PulseGuard.common.exception;

/**
 * Thrown when a monitor does not exist, or exists but belongs to another user.
 * Both cases map to 404 so the API never reveals other tenants' monitor ids.
 */
public class MonitorNotFoundException extends RuntimeException {
    public MonitorNotFoundException(Long monitorId) {
        super("Monitor not found: " + monitorId);
    }
}
