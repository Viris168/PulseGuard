package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.monitor.MonitorHeader;

/** A saved header as the API shows it. A secret header's value is never returned. */
public record HeaderView(String name, String value, boolean secret) {

    public static HeaderView from(MonitorHeader header) {
        boolean secret = MonitorHeader.isSecretName(header.getName());
        return new HeaderView(header.getName(), secret ? null : header.getValue(), secret);
    }
}
