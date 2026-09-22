package com.viris.PulseGuard.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;

@Embeddable
public record CheckDailyStatId(
        @Column(name = "monitor_id") Long monitorId,
        @Column(name = "day") LocalDate day) implements Serializable {
}
