package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.enumeration.ComponentStatus;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.OverallStatus;
import com.viris.PulseGuard.stats.StatsAssembler;
import com.viris.PulseGuard.stats.dto.Downtime;
import com.viris.PulseGuard.statuspage.dto.PublicComponent;
import com.viris.PulseGuard.statuspage.dto.PublicDay;
import com.viris.PulseGuard.statuspage.dto.PublicIncident;
import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a page's monitors, their daily check totals and their incidents into the public
 * payload. Pure, like StatsAssembler: every rule about what the public sees is testable
 * without Postgres. Uptime comes from checks, downtime from incidents, for the same reason
 * as on the dashboard.
 */
@Component
public class PublicStatusAssembler {

    static final Duration DAY = Duration.ofDays(1);

    /** A monitor as placed on the page. */
    public record ComponentInput(Long monitorId, String name, MonitorState state, boolean active) {
    }

    /** Checks of one monitor on day {@code index} (0 = the oldest day shown). */
    public record DayTotals(Long monitorId, int index, long total, long up) {
    }

    public record IncidentSpan(Long id, Long monitorId, IncidentStatus status, Instant startedAt, Instant resolvedAt) {
    }

    /**
     * @param from          start of the oldest day shown (UTC midnight)
     * @param incidentsFrom how far back the incident list reaches
     */
    public PublicStatusPageResponse assemble(String title, String description, int historyDays,
                                             Instant from, Instant now, Instant incidentsFrom,
                                             List<ComponentInput> inputs, List<DayTotals> totals,
                                             List<IncidentSpan> incidents) {
        Map<Long, Map<Integer, DayTotals>> byMonitor = totals.stream().collect(Collectors.groupingBy(
                DayTotals::monitorId, Collectors.toMap(DayTotals::index, Function.identity())));
        Map<Long, List<Downtime>> downtimes = incidents.stream().collect(Collectors.groupingBy(
                IncidentSpan::monitorId, Collectors.mapping(
                        i -> new Downtime(i.startedAt(), i.resolvedAt() != null ? i.resolvedAt() : now),
                        Collectors.toList())));

        List<PublicComponent> components = inputs.stream()
                .map(input -> component(input, historyDays, from,
                        byMonitor.getOrDefault(input.monitorId(), Map.of()),
                        downtimes.getOrDefault(input.monitorId(), List.of())))
                .toList();

        Map<Long, ComponentInput> placed = inputs.stream()
                .collect(Collectors.toMap(ComponentInput::monitorId, Function.identity()));
        List<PublicIncident> recent = incidents.stream()
                .filter(i -> placed.containsKey(i.monitorId()))
                .filter(i -> i.resolvedAt() == null || !i.resolvedAt().isBefore(incidentsFrom))
                .sorted(Comparator.comparing(IncidentSpan::startedAt).reversed())
                .map(i -> {
                    ComponentInput input = placed.get(i.monitorId());
                    boolean recovering = i.status() == IncidentStatus.OPEN && input.state() == MonitorState.RECOVERING;
                    return new PublicIncident(input.name(), i.status(), recovering, i.startedAt(), i.resolvedAt());
                })
                .toList();

        return new PublicStatusPageResponse(title, description, overall(components), historyDays,
                components, recent, now);
    }

    private static PublicComponent component(ComponentInput input, int historyDays, Instant from,
                                             Map<Integer, DayTotals> days, List<Downtime> downtimes) {
        List<PublicDay> list = new ArrayList<>(historyDays);
        long total = 0;
        long up = 0;
        for (int d = 0; d < historyDays; d++) {
            DayTotals day = days.get(d);
            Instant start = from.plus(DAY.multipliedBy(d));
            Instant end = start.plus(DAY);
            long downtime = downtimes.stream().mapToLong(t -> StatsAssembler.overlapSeconds(t, start, end)).sum();
            list.add(new PublicDay(start, day == null ? null : pct(day.total(), day.up()), downtime));
            if (day != null) {
                total += day.total();
                up += day.up();
            }
        }
        return new PublicComponent(input.name(), status(input), pct(total, up), list);
    }

    static ComponentStatus status(ComponentInput input) {
        if (!input.active()) {
            return ComponentStatus.PAUSED;
        }
        return switch (input.state()) {
            // Unconfirmed: the incident engine has opened nothing, so the public sees no alarm.
            case UP, SUSPICIOUS -> ComponentStatus.OPERATIONAL;
            case DOWN -> ComponentStatus.OUTAGE;
            case RECOVERING -> ComponentStatus.RECOVERING;
        };
    }

    /** Paused components are left out: a paused monitor says nothing about the service. */
    static OverallStatus overall(List<PublicComponent> components) {
        List<PublicComponent> live = components.stream().filter(c -> c.status() != ComponentStatus.PAUSED).toList();
        long outages = live.stream().filter(c -> c.status() == ComponentStatus.OUTAGE).count();
        if (outages > 0) {
            return outages == live.size() ? OverallStatus.MAJOR_OUTAGE : OverallStatus.PARTIAL_OUTAGE;
        }
        return live.stream().anyMatch(c -> c.status() == ComponentStatus.RECOVERING)
                ? OverallStatus.DEGRADED
                : OverallStatus.OPERATIONAL;
    }

    private static Double pct(long total, long up) {
        return total == 0 ? null : up * 100.0 / total;
    }
}
