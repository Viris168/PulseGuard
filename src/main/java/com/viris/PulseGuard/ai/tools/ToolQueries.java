package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.MonitorType;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.stats.CheckDailyStat;
import com.viris.PulseGuard.stats.CheckDailyStatRepository;
import com.viris.PulseGuard.stats.HousekeepingProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The database reads behind Ask AI's tools, each in a short read-only transaction, returning
 * plain values (tools run on a background thread, outside any transaction). Every method takes
 * ids the caller already checked against the {@link ToolScope}; {@link #sharedMonitors} is where
 * that list starts, scoped by user id in the query.
 */
@Service
public class ToolQueries {

    private final MonitorRepository monitors;
    private final CheckRepository checks;
    private final CheckDailyStatRepository dailyStats;
    private final IncidentRepository incidents;
    private final HousekeepingProperties housekeeping;

    public ToolQueries(MonitorRepository monitors, CheckRepository checks, CheckDailyStatRepository dailyStats,
                       IncidentRepository incidents, HousekeepingProperties housekeeping) {
        this.monitors = monitors;
        this.checks = checks;
        this.dailyStats = dailyStats;
        this.incidents = incidents;
        this.housekeeping = housekeeping;
    }

    /** A monitor as tools see it: no URL, headers or tokens. */
    public record MonitorRef(Long id, String name, MonitorType type, int intervalSeconds) {
    }

    /** The user's monitors that are shared with Ask AI. */
    @Transactional(readOnly = true)
    public List<MonitorRef> sharedMonitors(ToolScope scope) {
        return monitors.findAllByUserId(scope.userId()).stream()
                .filter(m -> scope.canSee(m.getId()))
                .map(m -> new MonitorRef(m.getId(), m.getName(), m.getType(), m.getIntervalSeconds()))
                .toList();
    }

    /** One day of checks. {@code avgMs} counts passing checks only, like the dashboard. */
    public record Day(LocalDate day, long total, long up, Integer avgMs) {
    }

    /**
     * Figures for [start, end). {@code fromDailySummaries}: the range reached past the raw checks
     * kept (pulseguard.housekeeping.raw-check-days), so figures come from the nightly summaries,
     * whose days are UTC days, and {@code p95Ms} is the worst day's p95.
     */
    public record Figures(long total, long up, Integer avgMs, Integer p95Ms, List<Day> days,
                          boolean fromDailySummaries) {
    }

    /**
     * Raw checks while they're kept: exact, with days that start at the user's midnight
     * ({@code start} is one). Older ranges: the nightly summaries.
     */
    @Transactional(readOnly = true)
    public Figures figures(Long monitorId, LocalDate from, Instant start, Instant end, Instant now) {
        Instant rawSince = now.minus(Duration.ofDays(housekeeping.rawCheckDays()));
        if (!start.isBefore(rawSince)) {
            CheckRepository.WindowTotalsRow w = checks.windowTotals(monitorId, start, end);
            List<Day> days = new ArrayList<>();
            // Buckets of 24 hours from the user's midnight: one per local day (a DST day is off by an hour).
            for (CheckRepository.BucketTotalsRow b : checks.bucketTotals(monitorId, start, end, Duration.ofDays(1).toSeconds())) {
                days.add(new Day(from.plusDays(b.getIdx()), b.getTotal(), b.getUp(), round(b.getAvgMs())));
            }
            return new Figures(w.getTotal(), w.getUp(), round(w.getAvgMs()), round(w.getP95Ms()), days, false);
        }
        LocalDate lastSummarised = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate to = end.atZone(ZoneOffset.UTC).toLocalDate();
        List<CheckDailyStat> stats = dailyStats.findByIdMonitorIdAndIdDayBetweenOrderByIdDay(
                monitorId, from, to.isAfter(lastSummarised) ? lastSummarised : to);
        long total = 0;
        long up = 0;
        double weightedAvg = 0;
        long weight = 0;
        Integer worstP95 = null;
        List<Day> days = new ArrayList<>();
        for (CheckDailyStat s : stats) {
            long dayUp = s.getTotalChecks() - s.getFailedChecks();
            total += s.getTotalChecks();
            up += dayUp;
            if (s.getAvgResponseMs() != null && dayUp > 0) {
                weightedAvg += (double) s.getAvgResponseMs() * dayUp;
                weight += dayUp;
            }
            if (s.getP95ResponseMs() != null && (worstP95 == null || s.getP95ResponseMs() > worstP95)) {
                worstP95 = s.getP95ResponseMs();
            }
            days.add(new Day(s.getId().day(), s.getTotalChecks(), dayUp, s.getAvgResponseMs()));
        }
        return new Figures(total, up, weight == 0 ? null : (int) Math.round(weightedAvg / weight), worstP95, days, true);
    }

    /** An incident as tools see it. */
    public record IncidentRef(Long id, String monitorName, Instant startedAt, Instant resolvedAt, String cause) {
    }

    /** Newest first; {@code limit + 1} rows at most, so the caller can tell whether there were more. */
    @Transactional(readOnly = true)
    public List<IncidentRef> incidents(Collection<Long> monitorIds, Instant start, Instant end, int limit) {
        if (monitorIds.isEmpty()) {
            return List.of();
        }
        return incidents.findInRangeNewestFirst(monitorIds, start, end, PageRequest.of(0, limit + 1)).stream()
                .map(i -> new IncidentRef(i.getId(), i.getMonitor().getName(), i.getStartedAt(), i.getResolvedAt(),
                        i.getCause()))
                .toList();
    }

    /** A failed check as tools see it. */
    public record Failure(Instant checkedAt, Integer statusCode, String errorType, String errorMessage,
                          Integer responseTimeMs) {
    }

    /**
     * Failed checks in [start, end): how many, and the newest {@code limit}. Single checks are only
     * kept for pulseguard.housekeeping.raw-check-days, so {@code pastRawChecks} says the window
     * reaches further back than that and older failures can't be listed.
     */
    public record Failures(long total, List<Failure> latest, boolean pastRawChecks, int rawCheckDays) {
    }

    @Transactional(readOnly = true)
    public Failures failures(Long monitorId, Instant start, Instant end, Instant now, int limit) {
        List<Failure> latest = checks
                .findByMonitorIdAndResultAndCheckedAtGreaterThanEqualAndCheckedAtLessThanOrderByCheckedAtDesc(
                        monitorId, CheckResult.DOWN, start, end, PageRequest.of(0, limit)).stream()
                .map(ToolQueries::failure)
                .toList();
        long total = checks.countByMonitorIdAndResultAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(
                monitorId, CheckResult.DOWN, start, end);
        int rawDays = housekeeping.rawCheckDays();
        return new Failures(total, latest, start.isBefore(now.minus(Duration.ofDays(rawDays))), rawDays);
    }

    private static Failure failure(Check c) {
        return new Failure(c.getCheckedAt(), c.getStatusCode(),
                c.getErrorType() == null ? null : c.getErrorType().name(), c.getErrorMessage(), c.getResponseTimeMs());
    }

    private static Integer round(Double value) {
        return value == null ? null : (int) Math.round(value);
    }
}
