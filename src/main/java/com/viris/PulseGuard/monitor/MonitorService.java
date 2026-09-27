package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.check.dto.CheckResponse;
import com.viris.PulseGuard.check.dto.RecentCheckRow;
import com.viris.PulseGuard.check.dto.UptimeRow;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.common.exception.PlanLimitExceededException;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.dto.MonitorRequest;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import com.viris.PulseGuard.monitor.dto.MonitorSummaryResponse;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final MonitorRepository monitorRepository;
    private final UserRepository userRepository;
    private final PlanLimits planLimits;
    private final SafeUrlValidator urlValidator;
    private final SchedulerService schedulerService;
    private final CheckRepository checkRepository;

    /** Length of the mini status bar on the monitors page. */
    static final int RECENT_CHECKS = 30;

    public MonitorService(MonitorRepository monitorRepository,
                          UserRepository userRepository,
                          PlanLimits planLimits,
                          SafeUrlValidator urlValidator,
                          SchedulerService schedulerService,
                          CheckRepository checkRepository) {
        this.monitorRepository = monitorRepository;
        this.userRepository = userRepository;
        this.planLimits = planLimits;
        this.urlValidator = urlValidator;
        this.schedulerService = schedulerService;
        this.checkRepository = checkRepository;
    }

    @Transactional
    public MonitorResponse createMonitor(Long userId, MonitorRequest request) {
        User user = requireUser(userId);
        Plan plan = user.getPlan();

        urlValidator.validate(request.url());

        int maxMonitors = planLimits.maxMonitors(plan);
        if (monitorRepository.countByUserId(userId) >= maxMonitors) {
            throw PlanLimitExceededException.atMost(plan, "monitors", maxMonitors);
        }
        requireAllowedInterval(plan, request.intervalSeconds());

        Monitor monitor = new Monitor();
        monitor.setUser(user);
        applyRequest(monitor, request);
        monitorRepository.save(monitor);
        schedulerService.schedule(monitor);

        log.info("Created monitorId={} for userId={}", monitor.getId(), userId);
        return MonitorResponse.from(monitor);
    }

    @Transactional(readOnly = true)
    public MonitorResponse getMonitor(Long userId, Long monitorId) {
        return MonitorResponse.from(requireOwnedMonitor(userId, monitorId));
    }

    @Transactional(readOnly = true)
    public List<MonitorSummaryResponse> listMonitors(Long userId) {
        List<Monitor> monitors = monitorRepository.findAllByUserId(userId);
        if (monitors.isEmpty()) {
            return List.of();
        }
        // Three queries in total, however many monitors: the list, one grouped uptime query,
        // one LATERAL query for every monitor's recent checks. Never one query per monitor.
        Map<Long, UptimeRow> uptime = checkRepository.uptimeSince(userId, Instant.now().minus(Duration.ofHours(24)))
                .stream().collect(Collectors.toMap(UptimeRow::monitorId, Function.identity()));
        Map<Long, List<RecentCheckRow>> recent = checkRepository.recentChecksPerMonitor(userId, RECENT_CHECKS)
                .stream().collect(Collectors.groupingBy(RecentCheckRow::getMonitorId));

        return monitors.stream().map(monitor -> {
            List<RecentCheckRow> rows = recent.getOrDefault(monitor.getId(), List.of());
            RecentCheckRow last = rows.isEmpty() ? null : rows.getLast();
            // Paused: yesterday's figure would read as current, so show none.
            Double uptime24h = monitor.isActive() && uptime.containsKey(monitor.getId())
                    ? uptime.get(monitor.getId()).uptimePct()
                    : null;
            return MonitorSummaryResponse.from(monitor, uptime24h,
                    last == null ? null : last.getResponseTimeMs(),
                    last == null ? null : last.getStatusCode(),
                    rows.stream().map(row -> "UP".equals(row.getResult())).toList());
        }).toList();
    }

    /**
     * Check history, optionally within [from, to], newest or oldest first. The ownership check
     * comes first: the checks query itself is keyed by monitor id alone, so without it any id
     * could be read (IDOR). Served by the {@code checks (monitor_id, checked_at)} index.
     */
    @Transactional(readOnly = true)
    public List<CheckResponse> recentChecks(Long userId, Long monitorId, int limit) {
        return checkHistory(userId, monitorId, null, null, limit, Sort.Direction.DESC);
    }

    @Transactional(readOnly = true)
    public List<CheckResponse> checkHistory(Long userId, Long monitorId, Instant from, Instant to,
                                            int limit, Sort.Direction direction) {
        requireOwnedMonitor(userId, monitorId);
        if (from == null && to == null && direction == Sort.Direction.DESC) {
            // The common case, "latest N": no range condition, straight down the index.
            return checkRepository.findByMonitorIdOrderByCheckedAtDesc(monitorId, PageRequest.of(0, limit)).stream()
                    .map(CheckResponse::from)
                    .toList();
        }
        return checkRepository.findByMonitorIdAndCheckedAtBetween(monitorId,
                        from != null ? from : Instant.EPOCH, to != null ? to : Instant.now(),
                        PageRequest.of(0, limit, Sort.by(direction, "checkedAt"))).stream()
                .map(CheckResponse::from)
                .toList();
    }

    @Transactional
    public MonitorResponse updateMonitor(Long userId, Long monitorId, MonitorRequest request) {
        Monitor monitor = requireOwnedMonitor(userId, monitorId);
        Plan plan = monitor.getUser().getPlan();

        // Re-validate only on change: an unchanged URL was cleared when it was saved,
        // and each check re-resolves it anyway.
        if (!monitor.getUrl().equals(request.url())) {
            urlValidator.validate(request.url());
        }
        requireAllowedInterval(plan, request.intervalSeconds());

        // Owner, state, counters and createdAt are deliberately untouched: the state
        // machine belongs to IncidentEngine, and editing a name must not mark a DOWN
        // monitor UP. The entity is managed, so dirty checking flushes these edits and
        // @Version guards the write.
        int oldInterval = monitor.getIntervalSeconds();
        applyRequest(monitor, request);
        // Only a new interval needs a new trigger; a paused monitor has no job to change,
        // and resume will schedule it with whatever interval it has by then.
        if (monitor.getIntervalSeconds() != oldInterval && monitor.isActive()) {
            schedulerService.reschedule(monitor);
        }

        log.info("Updated monitorId={} for userId={}", monitorId, userId);
        return MonitorResponse.from(monitor);
    }

    /** Pausing keeps history and incident state, and removes the monitor's check job. */
    @Transactional
    public MonitorResponse pauseMonitor(Long userId, Long monitorId) {
        return setActive(userId, monitorId, false);
    }

    @Transactional
    public MonitorResponse resumeMonitor(Long userId, Long monitorId) {
        return setActive(userId, monitorId, true);
    }

    @Transactional
    public void deleteMonitor(Long userId, Long monitorId) {
        monitorRepository.delete(requireOwnedMonitor(userId, monitorId));
        schedulerService.unschedule(monitorId);
        log.info("Deleted monitorId={} for userId={}", monitorId, userId);
    }

    private MonitorResponse setActive(Long userId, Long monitorId, boolean active) {
        Monitor monitor = requireOwnedMonitor(userId, monitorId);
        monitor.setActive(active);
        // After the ownership check: another tenant's id has already become a 404 here.
        // Resume schedules from scratch, because pausing deleted the job.
        if (active) {
            schedulerService.schedule(monitor);
        } else {
            schedulerService.unschedule(monitorId);
        }
        log.info("{} monitorId={} for userId={}", active ? "Resumed" : "Paused", monitorId, userId);
        return MonitorResponse.from(monitor);
    }

    private void applyRequest(Monitor monitor, MonitorRequest request) {
        monitor.setName(request.name());
        monitor.setUrl(request.url());
        monitor.setMethod(request.method());
        monitor.setExpectedStatus(request.expectedStatus());
        monitor.setIntervalSeconds(request.intervalSeconds());
        monitor.setTimeoutMs(request.timeoutMs());
    }

    /** Missing and other-tenant monitors both surface as 404, so ids cannot be probed. */
    private Monitor requireOwnedMonitor(Long userId, Long monitorId) {
        return monitorRepository.findByIdAndUserId(monitorId, userId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user " + userId + " no longer exists"));
    }

    private void requireAllowedInterval(Plan plan, int intervalSeconds) {
        int minInterval = planLimits.minIntervalSeconds(plan);
        if (intervalSeconds < minInterval) {
            throw PlanLimitExceededException.atLeast(plan, "seconds between checks", minInterval);
        }
    }
}
