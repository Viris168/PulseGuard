package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.stats.dto.Downtime;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import com.viris.PulseGuard.stats.dto.WindowTotals;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsAssemblerTest {

    private static final Instant TO = Instant.parse("2026-09-27T12:00:00Z");

    private final StatsAssembler assembler = new StatsAssembler();

    private MonitorRangeStats assemble(StatsRange range, WindowTotals totals, List<WindowTotals> buckets,
                                       List<Downtime> downtimes) {
        return assembler.assemble(range, TO.minus(range.span()), TO, totals, buckets, downtimes, null);
    }

    private static WindowTotals bucket(int index, long total, long up, Double avgMs) {
        return new WindowTotals(index, total, up, avgMs, null);
    }

    @Test
    void buildsTheBucketLayoutOfEachRange() {
        for (var expected : List.of(new int[]{96, 24}, new int[]{168, 28}, new int[]{180, 30})) {
            StatsRange range = expected[0] == 96 ? StatsRange.H24 : expected[0] == 168 ? StatsRange.D7 : StatsRange.D30;

            MonitorRangeStats stats = assemble(range, WindowTotals.empty(), List.of(), List.of());

            assertThat(stats.responseSeries()).hasSize(expected[0]);
            assertThat(stats.uptimeBuckets()).hasSize(expected[1]);
            // Contiguous and exactly covering the window.
            assertThat(stats.responseSeries().getFirst().start()).isEqualTo(TO.minus(range.span()));
            assertThat(stats.responseSeries().getLast().end()).isEqualTo(TO);
            assertThat(stats.uptimeBuckets().getLast().end()).isEqualTo(TO);
        }
    }

    @Test
    void bucketsWithoutChecksAreNullNotZero() {
        MonitorRangeStats stats = assemble(StatsRange.H24, new WindowTotals(-1, 1, 1, 120.0, 120.0),
                List.of(bucket(95, 1, 1, 120.4)), List.of());

        assertThat(stats.responseSeries().get(0).avgResponseMs()).isNull();
        assertThat(stats.responseSeries().get(0).uptimePct()).isNull();
        assertThat(stats.responseSeries().get(95).avgResponseMs()).isEqualTo(120);
        assertThat(stats.responseSeries().get(95).uptimePct()).isEqualTo(100.0);
    }

    @Test
    void uptimeBarsMergeTheirChartBucketsByCountNotByAveragingPercentages() {
        // Hour bar 0 = 15-min buckets 0..3: 1 of 2 up, then 2 of 2 up. 3 of 4 = 75%, not (50+100)/2.
        MonitorRangeStats stats = assemble(StatsRange.H24, new WindowTotals(-1, 4, 3, null, null),
                List.of(bucket(0, 2, 1, null), bucket(3, 2, 2, null)), List.of());

        assertThat(stats.uptimeBuckets().getFirst().uptimePct()).isEqualTo(75.0);
    }

    @Test
    void downtimeIsClippedToTheWindowAndToEachBar() {
        Instant from = TO.minus(StatsRange.H24.span());
        // Started 30 min before the window, ended 30 min into it: only 30 min count.
        Downtime outage = new Downtime(from.minus(Duration.ofMinutes(30)), from.plus(Duration.ofMinutes(30)));

        MonitorRangeStats stats = assemble(StatsRange.H24, WindowTotals.empty(), List.of(), List.of(outage));

        assertThat(stats.downtimeSeconds()).isEqualTo(1800);
        assertThat(stats.incidentCount()).isEqualTo(1);
        assertThat(stats.uptimeBuckets().get(0).downtimeSeconds()).isEqualTo(1800);
        assertThat(stats.uptimeBuckets().get(1).downtimeSeconds()).isZero();
    }

    @Test
    void aFailedCheckLowersUptimeButOnlyAnIncidentCountsAsDowntime() {
        MonitorRangeStats stats = assemble(StatsRange.H24, new WindowTotals(-1, 4, 3, 100.0, 180.0),
                List.of(bucket(95, 4, 3, 100.0)), List.of());

        assertThat(stats.uptimePct()).isEqualTo(75.0);
        assertThat(stats.downtimeSeconds()).isZero();
        assertThat(stats.p95ResponseMs()).isEqualTo(180);
    }

    @Test
    void noChecksMeansUnknownNotZeroPercent() {
        MonitorRangeStats stats = assemble(StatsRange.H24, WindowTotals.empty(), List.of(), List.of());

        assertThat(stats.uptimePct()).isNull();
        assertThat(stats.avgResponseMs()).isNull();
        assertThat(stats.checksCount()).isZero();
    }

    @Test
    void overlapIsZeroForDisjointIntervals() {
        Downtime earlier = new Downtime(TO.minusSeconds(100), TO.minusSeconds(50));

        assertThat(StatsAssembler.overlapSeconds(earlier, TO.minusSeconds(40), TO)).isZero();
        assertThat(StatsAssembler.overlapSeconds(earlier, TO.minusSeconds(60), TO)).isEqualTo(10);
    }
}
