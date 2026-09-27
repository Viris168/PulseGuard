package com.viris.PulseGuard.check.dto;

/** Per-monitor check counts over a window, filled by a JPQL constructor expression. */
public record UptimeRow(Long monitorId, Long total, Long up) {

    /** 0–100, or null when there were no checks to judge by. */
    public Double uptimePct() {
        return total == 0 ? null : up * 100.0 / total;
    }
}
