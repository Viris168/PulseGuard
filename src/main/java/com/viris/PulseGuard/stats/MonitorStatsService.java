package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.common.exception.PlanLimitExceededException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard figures for one monitor over 24h / 7d / 30d. Computed from raw checks for every
 * range: the charts need hourly and 4-hourly points, which a one-row-per-day rollup cannot
 * give, and the aggregation runs in Postgres so at most a few hundred rows come back.
 * A daily rollup becomes necessary only once old raw checks are pruned (retention job).
 *
 * <p>Ownership and plan limits run on every request, before the (cached) calculation.
 */
@Service
public class MonitorStatsService {

    private final MonitorRepository monitorRepository;
    private final PlanLimits planLimits;
    private final StatsCalculator calculator;

    public MonitorStatsService(MonitorRepository monitorRepository, PlanLimits planLimits, StatsCalculator calculator) {
        this.monitorRepository = monitorRepository;
        this.planLimits = planLimits;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public MonitorRangeStats stats(Long userId, Long monitorId, StatsRange range) {
        Monitor monitor = monitorRepository.findByIdAndUserId(monitorId, userId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));

        // Plan limits are enforced here, server-side, never trusted to the UI.
        Plan plan = monitor.getUser().getPlan();
        int retentionDays = planLimits.retentionDays(plan);
        if (range.days() > retentionDays) {
            throw PlanLimitExceededException.atMost(plan, "days of history", retentionDays);
        }
        return calculator.compute(monitorId, range, range.days() * 2 <= retentionDays);
    }
}
