package com.viris.PulseGuard.statuspage.dto;

/** A monitor on the page, shown publicly as {@code displayName}; its URL never is. */
public record StatusPageMonitorDto(Long monitorId, String displayName) {
}
