package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.stats.dto.Downtime;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import com.viris.PulseGuard.stats.dto.PeriodSummary;
import com.viris.PulseGuard.stats.dto.SeriesPoint;
import com.viris.PulseGuard.stats.dto.UptimeBucket;
import com.viris.PulseGuard.stats.dto.WindowTotals;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns aggregated check totals and incident intervals into the dashboard payload. Pure: the
 * database work is done before this runs, so every rule here is testable without Postgres.
 *
 * <p>Two sources, on purpose: uptime percentages come from checks (what was observed),
 * downtime seconds from incidents (what was declared an outage). A single failed check
 * lowers uptime but adds no downtime, because it never became an incident.
 */
@Component
public class StatsAssembler {

    public PeriodSummary summary(WindowTotals totals, List<Downtime> downtimes, Instant from, Instant to) {
        List<Downtime> overlapping = downtimes.stream().filter(d -> overlapSeconds(d, from, to) > 0).toList();
        return new PeriodSummary(uptimePct(totals.total(), totals.up()), round(totals.avgMs()), round(totals.p95Ms()),
                totals.total(), overlapping.size(),
                overlapping.stream().mapToLong(d -> overlapSeconds(d, from, to)).sum());
    }

    public MonitorRangeStats assemble(StatsRange range, Instant from, Instant to, WindowTotals totals,
                                      List<WindowTotals> buckets, List<Downtime> downtimes, PeriodSummary previous) {
        Duration size = range.seriesBucket();
        int count = (int) (range.span().toSeconds() / size.toSeconds());
        Map<Integer, WindowTotals> byIndex = buckets.stream()
                .collect(Collectors.toMap(WindowTotals::index, Function.identity()));

        List<SeriesPoint> series = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WindowTotals bucket = byIndex.getOrDefault(i, WindowTotals.empty());
            Instant start = from.plus(size.multipliedBy(i));
            series.add(new SeriesPoint(start, start.plus(size), round(bucket.avgMs()),
                    uptimePct(bucket.total(), bucket.up())));
        }

        int merge = range.seriesBucketsPerUptimeBucket();
        List<UptimeBucket> bars = new ArrayList<>(count / merge);
        for (int bar = 0; bar < count / merge; bar++) {
            long total = 0;
            long up = 0;
            for (int i = bar * merge; i < (bar + 1) * merge; i++) {
                WindowTotals bucket = byIndex.getOrDefault(i, WindowTotals.empty());
                total += bucket.total();
                up += bucket.up();
            }
            Instant start = from.plus(size.multipliedBy((long) bar * merge));
            Instant end = start.plus(size.multipliedBy(merge));
            long downtime = downtimes.stream().mapToLong(d -> overlapSeconds(d, start, end)).sum();
            bars.add(new UptimeBucket(start, end, uptimePct(total, up), downtime));
        }

        PeriodSummary current = summary(totals, downtimes, from, to);
        return new MonitorRangeStats(range, current.uptimePct(), current.avgResponseMs(), current.p95ResponseMs(),
                current.checksCount(), current.incidentCount(), current.downtimeSeconds(), series, bars, previous);
    }

    /** Seconds of {@code downtime} inside [from, to); zero when they do not overlap. */
    public static long overlapSeconds(Downtime downtime, Instant from, Instant to) {
        Instant start = downtime.start().isAfter(from) ? downtime.start() : from;
        Instant end = downtime.end().isBefore(to) ? downtime.end() : to;
        return end.isAfter(start) ? Duration.between(start, end).toSeconds() : 0;
    }

    private static Double uptimePct(long total, long up) {
        return total == 0 ? null : up * 100.0 / total;
    }

    private static Integer round(Double value) {
        return value == null ? null : (int) Math.round(value);
    }
}
