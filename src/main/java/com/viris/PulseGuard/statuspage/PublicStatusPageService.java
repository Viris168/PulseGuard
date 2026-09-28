package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.common.exception.StatusPageNotFoundException;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.stats.CheckDailyStatRepository;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.ComponentInput;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.DayTotals;
import com.viris.PulseGuard.statuspage.PublicStatusAssembler.IncidentSpan;
import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private final CheckDailyStatRepository dailyStatRepository;
    private final IncidentRepository incidentRepository;
    private final PlanLimits planLimits;
    private final PublicStatusAssembler assembler;

    public PublicStatusPageService(StatusPageRepository statusPageRepository,
                                   MonitorRepository monitorRepository,
                                   CheckRepository checkRepository,
                                   CheckDailyStatRepository dailyStatRepository,
                                   IncidentRepository incidentRepository,
                                   PlanLimits planLimits,
                                   PublicStatusAssembler assembler) {
        this.statusPageRepository = statusPageRepository;
        this.monitorRepository = monitorRepository;
        this.checkRepository = checkRepository;
        this.dailyStatRepository = dailyStatRepository;
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
            totals = dayTotals(ids, from, now);
            Instant earliest = from.isBefore(incidentsFrom) ? from : incidentsFrom;
            incidents = incidentRepository.findOverlappingForMonitors(ids, earliest, now).stream()
                    .map(i -> new IncidentSpan(i.getId(), i.getMonitor().getId(), i.getStatus(),
                            i.getStartedAt(), i.getResolvedAt()))
                    .toList();
        }

        return assembler.assemble(page.getTitle(), page.getDescription(), historyDays,
                from, now, incidentsFrom, inputs, totals, incidents);
    }

    /**
     * One total per monitor per day. Days already summarised come from check_daily_stats, since
     * raw checks are deleted after a while; the rest (today, and yesterday until the nightly run)
     * from raw checks. Indexes are day offsets from {@code from}, as the assembler expects.
     */
    private List<DayTotals> dayTotals(List<Long> ids, Instant from, Instant now) {
        LocalDate fromDay = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate latest = dailyStatRepository.latestDay();
        LocalDate rawFromDay = latest == null ? fromDay : latest.plusDays(1);
        if (rawFromDay.isBefore(fromDay)) {
            rawFromDay = fromDay;
        }
        if (rawFromDay.isAfter(today)) {
            rawFromDay = today;
        }

        List<DayTotals> totals = new ArrayList<>();
        if (rawFromDay.isAfter(fromDay)) {
            for (CheckDailyStatRepository.DailyTotalsRow r : dailyStatRepository.dailyTotalsForMonitors(ids, fromDay, rawFromDay)) {
                int index = (int) ChronoUnit.DAYS.between(fromDay, r.getDay());
                totals.add(new DayTotals(r.getMonitorId(), index, r.getTotal(), r.getTotal() - r.getFailed()));
            }
        }
        int offset = (int) ChronoUnit.DAYS.between(fromDay, rawFromDay);
        Instant rawFrom = rawFromDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (CheckRepository.MonitorBucketRow r : checkRepository.bucketTotalsForMonitors(ids, rawFrom, now,
                PublicStatusAssembler.DAY.toSeconds())) {
            totals.add(new DayTotals(r.getMonitorId(), offset + r.getIdx(), r.getTotal(), r.getUp()));
        }
        return totals;
    }
}
