package com.viris.PulseGuard.statuspage.dto;

import com.viris.PulseGuard.enumeration.ComponentStatus;

import java.util.List;

/** One monitor as the public sees it: its display name, never its own name or URL. */
public record PublicComponent(String name, ComponentStatus status, Double uptimePct, List<PublicDay> days) {
}
