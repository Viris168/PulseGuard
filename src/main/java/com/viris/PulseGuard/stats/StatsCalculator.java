package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.check.CheckRepository.WindowTotalsRow;
import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.stats.dto.Downtime;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import com.viris.PulseGuard.stats.dto.PeriodSummary;
import com.viris.PulseGuard.stats.dto.WindowTotals;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The expensive part of the stats endpoint, cached in Redis. Its own bean on purpose:
 * {@code @Cacheable} works through a proxy, and a call from inside the same class never
 * passes through it, so the cache would be silently skipped.
 *
 * <p>No authorization here, by design: the key is only a monitor id, so the caller must
 * have checked ownership first, on every request, warm cache or not.
 * {@code withPrevious} is in the key because it depends on the plan: after an upgrade the
 * next request is a miss and gets the comparison.
 */
@Component
public class StatsCalculator {

    private final CheckRepository checkRepository;
    private final IncidentRepository incidentRepository;
    private final StatsAssembler assembler;

    public StatsCalculator(CheckRepository checkRepository,
                           IncidentRepository incidentRepository,
                           StatsAssembler assembler) {
        this.checkRepository = checkRepository;
        this.incidentRepository = incidentRepository;
        this.assembler = assembler;
    }

    @Cacheable(cacheNames = StatsCacheConfig.MONITOR_STATS,
            key = "#monitorId + ':' + #range.label() + ':' + #withPrevious")
    @Transactional(readOnly = true)
    public MonitorRangeStats compute(Long monitorId, StatsRange range, boolean withPrevious) {
        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(range.span());
        Instant previousFrom = from.minus(range.span());

        List<Downtime> downtimes = incidentRepository
                .findOverlapping(monitorId, withPrevious ? previousFrom : from, to).stream()
                .map(i -> new Downtime(i.getStartedAt(), i.getResolvedAt() != null ? i.getResolvedAt() : to))
                .toList();

        WindowTotals totals = totals(checkRepository.windowTotals(monitorId, from, to));
        List<WindowTotals> buckets = checkRepository
                .bucketTotals(monitorId, from, to, range.seriesBucket().toSeconds()).stream()
                .map(row -> new WindowTotals(row.getIdx(), row.getTotal(), row.getUp(), row.getAvgMs(), null))
                .toList();

        PeriodSummary previous = null;
        if (withPrevious) {
            WindowTotals previousTotals = totals(checkRepository.windowTotals(monitorId, previousFrom, from));
            if (previousTotals.total() > 0) {
                previous = assembler.summary(previousTotals, downtimes, previousFrom, from);
            }
        }
        return assembler.assemble(range, from, to, totals, buckets, downtimes, previous);
    }

    private static WindowTotals totals(WindowTotalsRow row) {
        return new WindowTotals(-1, row.getTotal(), row.getUp(), row.getAvgMs(), row.getP95Ms());
    }
}
