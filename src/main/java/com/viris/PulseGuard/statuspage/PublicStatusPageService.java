package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.common.exception.StatusPageNotFoundException;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.ComponentInput;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.DayTotals;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.IncidentSpan;
import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The public page, rendered for anyone who has the link. Cached in Redis by slug, because
 * it is unauthenticated and so can be requested far more often than any dashboard. Saving
 * the page evicts it (StatusPageCacheEvictor); other changes show within the cache TTL.
 *
 * <p>Three queries however many monitors: the page, one grouped daily-totals query, one
 * incidents query. Monitors are looked up through the page owner, so a page can never show
 * another account's monitor.
 */
@Service
public class PublicStatusPageService {

    /** The bars stay readable up to 90; longer retention (Business) still shows 90 days. */
    static final int MAX_HISTORY_DAYS = 90;
    static final Duration INCIDENT_WINDOW = Duration.ofDays(14);

    private final StatusPageRepository statusPageRepository;
    private final MonitorRepository monitorRepository;
    private final CheckRepository checkRepository;
    private final IncidentRepository incidentRepository;
    private final PlanLimits planLimits;
    private final PublicStatusAssembler assembler;

    public PublicStatusPageService(StatusPageRepository statusPageRepository,
                                   MonitorRepository monitorRepository,
                                   CheckRepository checkRepository,
                                   IncidentRepository incidentRepository,
                                   PlanLimits planLimits,
                                   PublicStatusAssembler assembler) {
        this.statusPageRepository = statusPageRepository;
        this.monitorRepository = monitorRepository;
        this.checkRepository = checkRepository;
        this.incidentRepository = incidentRepository;
        this.planLimits = planLimits;
        this.assembler = assembler;
    }

    /** @param slug already lower-cased, so every spelling shares one cache entry */
    @Cacheable(cacheNames = StatusPageCacheConfig.PUBLIC_STATUS_PAGES, key = "#slug")
    @Transactional(readOnly = true)
    public PublicStatusPageResponse render(String slug) {
        // Unpublished and missing are the same 404: a draft's address must not leak.
        StatusPage page = statusPageRepository.findBySlug(slug)
                .filter(StatusPage::isPublished)
                .orElseThrow(StatusPageNotFoundException::new);
        User owner = page.getUser();
        int historyDays = Math.min(MAX_HISTORY_DAYS, planLimits.retentionDays(owner.getPlan()));

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = now.truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(historyDays - 1L));
        Instant incidentsFrom = now.minus(INCIDENT_WINDOW);

        Map<Long, Monitor> owned = monitorRepository.findAllByUserId(owner.getId()).stream()
                .collect(Collectors.toMap(Monitor::getId, Function.identity()));
        List<ComponentInput> inputs = page.getEntries().stream()
                .filter(e -> owned.containsKey(e.getMonitorId()))
                .map(e -> {
                    Monitor m = owned.get(e.getMonitorId());
                    return new ComponentInput(m.getId(), e.getDisplayName(), m.getState(), m.isActive());
                })
                .toList();

        List<DayTotals> totals = List.of();
        List<IncidentSpan> incidents = List.of();
        if (!inputs.isEmpty()) {
            List<Long> ids = inputs.stream().map(ComponentInput::monitorId).toList();
            totals = checkRepository.bucketTotalsForMonitors(ids, from, now, PublicStatusAssembler.DAY.toSeconds())
                    .stream()
                    .map(r -> new DayTotals(r.getMonitorId(), r.getIdx(), r.getTotal(), r.getUp()))
                    .toList();
            Instant earliest = from.isBefore(incidentsFrom) ? from : incidentsFrom;
            incidents = incidentRepository.findOverlappingForMonitors(ids, earliest, now).stream()
                    .map(i -> new IncidentSpan(i.getId(), i.getMonitor().getId(), i.getStatus(),
                            i.getStartedAt(), i.getResolvedAt()))
                    .toList();
        }

        return assembler.assemble(page.getTitle(), page.getDescription(), historyDays,
                from, now, incidentsFrom, inputs, totals, incidents);
    }
}
