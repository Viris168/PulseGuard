package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.monitor.Monitor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "check_daily_stats")
@Getter
@Setter
@NoArgsConstructor
public class CheckDailyStat {

    @EmbeddedId
    private CheckDailyStatId id;

    @MapsId("monitorId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id")
    private Monitor monitor;

    @Column(name = "total_checks", nullable = false)
    private int totalChecks;

    @Column(name = "failed_checks", nullable = false)
    private int failedChecks;

    @Column(name = "avg_response_ms")
    private Integer avgResponseMs;

    @Column(name = "p95_response_ms")
    private Integer p95ResponseMs;
}
