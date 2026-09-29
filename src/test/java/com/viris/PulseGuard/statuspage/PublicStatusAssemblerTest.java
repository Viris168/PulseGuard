package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.enumeration.ComponentStatus;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.OverallStatus;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.ComponentInput;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.DayTotals;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.IncidentSpan;
import com.viris.PulseGuard.statuspage.dto.PublicComponent;
import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PublicStatusAssemblerTest {

    private final PublicStatusAssembler assembler = new PublicStatusAssembler();

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-03T12:00:00Z");
    private static final Instant INCIDENTS_FROM = NOW.minus(Duration.ofDays(14));

    private PublicStatusPageResponse assemble(List<ComponentInput> inputs, List<DayTotals> totals,
                                              List<IncidentSpan> incidents) {
        return assembler.assemble("Acme", "", 3, FROM, NOW, INCIDENTS_FROM, inputs, totals, incidents);
    }

    private static ComponentInput up(long id, String name) {
        return new ComponentInput(id, name, MonitorState.UP, true);
    }

    @Test
    void mapsMonitorStatesToPublicWording() {
        assertThat(PublicStatusAssembler.status(new ComponentInput(1L, "a", MonitorState.UP, true)))
                .isEqualTo(ComponentStatus.OPERATIONAL);
        assertThat(PublicStatusAssembler.status(new ComponentInput(1L, "a", MonitorState.SUSPICIOUS, true)))
                .isEqualTo(ComponentStatus.OPERATIONAL);
        assertThat(PublicStatusAssembler.status(new ComponentInput(1L, "a", MonitorState.DOWN, true)))
                .isEqualTo(ComponentStatus.OUTAGE);
        assertThat(PublicStatusAssembler.status(new ComponentInput(1L, "a", MonitorState.RECOVERING, true)))
                .isEqualTo(ComponentStatus.RECOVERING);
        assertThat(PublicStatusAssembler.status(new ComponentInput(1L, "a", MonitorState.DOWN, false)))
                .isEqualTo(ComponentStatus.PAUSED);
    }

    private static PublicComponent component(ComponentStatus status) {
        return new PublicComponent("x", status, null, List.of());
    }

    @Test
    void everyLiveComponentDownIsAMajorOutage() {
        assertThat(PublicStatusAssembler.overall(List.of(component(ComponentStatus.OUTAGE),
                component(ComponentStatus.PAUSED)))).isEqualTo(OverallStatus.MAJOR_OUTAGE);
    }

    @Test
    void someComponentsDownIsAPartialOutage() {
        assertThat(PublicStatusAssembler.overall(List.of(component(ComponentStatus.OUTAGE),
                component(ComponentStatus.OPERATIONAL)))).isEqualTo(OverallStatus.PARTIAL_OUTAGE);
    }

    @Test
    void aRecoveringComponentIsDegraded() {
        assertThat(PublicStatusAssembler.overall(List.of(component(ComponentStatus.RECOVERING),
                component(ComponentStatus.OPERATIONAL)))).isEqualTo(OverallStatus.DEGRADED);
    }

    @Test
    void noComponentsOrAllPausedIsOperational() {
        assertThat(PublicStatusAssembler.overall(List.of())).isEqualTo(OverallStatus.OPERATIONAL);
        assertThat(PublicStatusAssembler.overall(List.of(component(ComponentStatus.PAUSED))))
                .isEqualTo(OverallStatus.OPERATIONAL);
    }

    @Test
    void bucketsChecksIntoDaysAndIncidentsIntoDowntime() {
        List<DayTotals> totals = List.of(new DayTotals(1L, 0, 10, 9), new DayTotals(1L, 2, 4, 4));
        // 23:00 on day 0 to 01:00 on day 1: an hour on each side of midnight.
        List<IncidentSpan> incidents = List.of(new IncidentSpan(7L, 1L, IncidentStatus.RESOLVED,
                Instant.parse("2026-01-01T23:00:00Z"), Instant.parse("2026-01-02T01:00:00Z")));

        PublicComponent api = assemble(List.of(up(1L, "API")), totals, incidents).components().getFirst();

        assertThat(api.days()).hasSize(3);
        assertThat(api.days().get(0).date()).isEqualTo(FROM);
        assertThat(api.days().get(0).uptimePct()).isEqualTo(90.0);
        assertThat(api.days().get(0).downtimeSeconds()).isEqualTo(3600);
        assertThat(api.days().get(1).uptimePct()).isNull(); // no checks that day
        assertThat(api.days().get(1).downtimeSeconds()).isEqualTo(3600);
        assertThat(api.days().get(2).uptimePct()).isEqualTo(100.0);
        assertThat(api.uptimePct()).isCloseTo(13 * 100.0 / 14, within(1e-9));
    }

    @Test
    void anOpenIncidentCountsAsDowntimeUntilNow() {
        List<IncidentSpan> incidents = List.of(new IncidentSpan(7L, 1L, IncidentStatus.OPEN,
                Instant.parse("2026-01-03T11:00:00Z"), null));

        PublicComponent api = assemble(List.of(up(1L, "API")), List.of(), incidents).components().getFirst();

        assertThat(api.days().get(2).downtimeSeconds()).isEqualTo(3600);
    }

    @Test
    void listsRecentIncidentsNewestFirstUnderTheirPublicName() {
        List<IncidentSpan> incidents = List.of(
                new IncidentSpan(1L, 1L, IncidentStatus.RESOLVED,
                        NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)).plusSeconds(600)),
                new IncidentSpan(2L, 2L, IncidentStatus.OPEN, NOW.minus(Duration.ofHours(1)), null),
                // Resolved before the 14-day window: left out.
                new IncidentSpan(3L, 1L, IncidentStatus.RESOLVED,
                        NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(19))),
                // A monitor that is not on the page: left out.
                new IncidentSpan(4L, 99L, IncidentStatus.OPEN, NOW.minus(Duration.ofHours(2)), null));
        List<ComponentInput> inputs = List.of(up(1L, "API"),
                new ComponentInput(2L, "Payments", MonitorState.RECOVERING, true));

        var listed = assemble(inputs, List.of(), incidents).incidents();

        assertThat(listed).extracting("componentName").containsExactly("Payments", "API");
        assertThat(listed.getFirst().componentName()).isEqualTo("Payments");
        assertThat(listed.getFirst().recovering()).isTrue();
        assertThat(listed.get(1).recovering()).isFalse();
    }
}
