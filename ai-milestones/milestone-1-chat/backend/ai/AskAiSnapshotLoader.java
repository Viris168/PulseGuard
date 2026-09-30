package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.check.dto.UptimeRow;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads one user's {@link AskAiSnapshot}: only monitors their Ask AI access allows, and only
 * through queries scoped by their user id. A fixed number of queries whatever the monitor count
 * (plus one per failing monitor, capped), so a question never scans more than it must.
 */
@Component
public class AskAiSnapshotLoader {

    /** Keeps the prompt, and so each question's cost, bounded for large accounts. */
    static final int MAX_MONITORS = 50;
    static final int MAX_INCIDENTS = 20;
    /** Failing monitors whose latest error is looked up; one small query each. */
    static final int MAX_FAILURE_LOOKUPS = 10;
    static final Duration INCIDENT_WINDOW = Duration.ofDays(7);

    private final MonitorRepository monitorRepository;
    private final CheckRepository checkRepository;
    private final IncidentRepository incidentRepository;
    private final Clock clock;

    @Autowired
    public AskAiSnapshotLoader(MonitorRepository monitorRepository, CheckRepository checkRepository,
                               IncidentRepository incidentRepository) {
        this(monitorRepository, checkRepository, incidentRepository, Clock.systemUTC());
    }

    AskAiSnapshotLoader(MonitorRepository monitorRepository, CheckRepository checkRepository,
                        IncidentRepository incidentRepository, Clock clock) {
        this.monitorRepository = monitorRepository;
        this.checkRepository = checkRepository;
        this.incidentRepository = incidentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AskAiSnapshot load(Long userId, AiAccess access, ZoneId zone) {
        Instant now = clock.instant();
        List<Monitor> all = monitorRepository.findAllByUserId(userId);
        List<Monitor> shared = all.stream()
                .filter(m -> access.allows(m.getId()))
                // Monitors with problems first, so a cap never drops the ones people ask about.
                .sorted(Comparator.comparing(AskAiSnapshotLoader::healthy).thenComparing(Monitor::getName))
                .toList();
        List<Monitor> included = shared.stream().limit(MAX_MONITORS).toList();
        Set<Long> includedIds = included.stream().map(Monitor::getId).collect(Collectors.toSet());

        Map<Long, UptimeRow> day = checkRepository.uptimeSince(userId, now.minus(Duration.ofHours(24))).stream()
                .collect(Collectors.toMap(UptimeRow::monitorId, Function.identity()));
        Map<Long, CheckRepository.MonitorTotalsRow> week =
                checkRepository.totalsPerMonitorSince(userId, now.minus(Duration.ofDays(7))).stream()
                        .collect(Collectors.toMap(CheckRepository.MonitorTotalsRow::getMonitorId, Function.identity()));
        Set<Long> failureLookups = included.stream().filter(m -> !healthy(m)).limit(MAX_FAILURE_LOOKUPS)
                .map(Monitor::getId).collect(Collectors.toSet());

        List<AskAiSnapshot.MonitorFacts> monitors = included.stream().map(m -> {
            UptimeRow d = day.get(m.getId());
            CheckRepository.MonitorTotalsRow w = week.get(m.getId());
            return new AskAiSnapshot.MonitorFacts(m.getId(), m.getName(), m.getType(), m.getState(), m.isActive(),
                    m.getIntervalSeconds(), m.getLastCheckedAt(),
                    d == null ? null : d.uptimePct(),
                    w == null || w.getTotal() == 0 ? null : w.getUp() * 100.0 / w.getTotal(),
                    w == null || w.getAvgMs() == null ? null : (int) Math.round(w.getAvgMs()),
                    w == null || w.getP95Ms() == null ? null : (int) Math.round(w.getP95Ms()),
                    failureLookups.contains(m.getId()) ? lastFailure(m.getId()) : null);
        }).toList();

        Instant since = now.minus(INCIDENT_WINDOW);
        List<AskAiSnapshot.IncidentFacts> incidents = incidentRepository
                .search(userId, null, null, PageRequest.of(0, MAX_INCIDENTS * 5)).stream()
                .filter(i -> includedIds.contains(i.getMonitor().getId()))
                .filter(i -> i.getStatus() == IncidentStatus.OPEN || !i.getStartedAt().isBefore(since))
                .limit(MAX_INCIDENTS)
                .map(AskAiSnapshotLoader::facts)
                .toList();

        return new AskAiSnapshot(now, zone, monitors, shared.size() - included.size(),
                all.size() - shared.size(), incidents);
    }

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** The browser's IANA time zone, or UTC when it's missing or not a real zone. */
    public static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return UTC;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return UTC;
        }
    }

    private static boolean healthy(Monitor monitor) {
        return !monitor.isActive() || monitor.getState() == MonitorState.UP;
    }

    /** The newest failed check among the last few, as "TYPE: message", or null. */
    private String lastFailure(Long monitorId) {
        return checkRepository.findByMonitorIdOrderByCheckedAtDesc(monitorId, PageRequest.of(0, 5)).stream()
                .filter(c -> c.getResult() == CheckResult.DOWN)
                .findFirst()
                .map(AskAiSnapshotLoader::describe)
                .orElse(null);
    }

    private static String describe(Check check) {
        String type = check.getErrorType() == null ? "FAILED" : check.getErrorType().name();
        if (check.getErrorMessage() != null) {
            return type + ": " + check.getErrorMessage();
        }
        return check.getStatusCode() == null ? type : type + ": HTTP " + check.getStatusCode();
    }

    private static AskAiSnapshot.IncidentFacts facts(Incident incident) {
        return new AskAiSnapshot.IncidentFacts(incident.getMonitor().getName(),
                incident.getStatus() == IncidentStatus.OPEN, incident.getStartedAt(),
                incident.getResolvedAt(), incident.getCause());
    }
}
