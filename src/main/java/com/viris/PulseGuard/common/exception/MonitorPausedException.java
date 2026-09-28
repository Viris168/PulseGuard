package com.viris.PulseGuard.common.exception;

public class MonitorPausedException extends RuntimeException {
    public MonitorPausedException() {
        super("This monitor is paused. Resume it to record pings.");
    }
}
