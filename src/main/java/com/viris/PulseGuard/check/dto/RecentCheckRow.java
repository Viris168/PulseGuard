package com.viris.PulseGuard.check.dto;

/** Interface projection for the native LATERAL query: Spring Data maps columns by alias. */
public interface RecentCheckRow {

    Long getMonitorId();

    /** "UP" or "DOWN"; a native query returns the stored text, not the enum. */
    String getResult();

    Integer getStatusCode();

    Integer getResponseTimeMs();
}
