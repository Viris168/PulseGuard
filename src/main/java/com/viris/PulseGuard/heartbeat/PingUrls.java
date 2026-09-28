package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.monitor.Monitor;
import org.springframework.stereotype.Component;

/** Builds a heartbeat's public ping URL from the configured origin. */
@Component
public class PingUrls {

    private final String base;

    public PingUrls(HeartbeatProperties properties) {
        String url = properties.pingBaseUrl();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Null for HTTP monitors, which have no ping URL. */
    public String of(Monitor monitor) {
        return monitor.isHeartbeat() ? base + "/api/ping/" + monitor.getHeartbeatToken() : null;
    }
}
