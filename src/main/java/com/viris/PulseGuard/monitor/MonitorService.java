package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.apikey.ApiKeyAuthenticationToken;
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
import com.viris.PulseGuard.common.exception.InvalidMonitorException;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.MonitorType;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.heartbeat.HeartbeatSchedule;
import com.viris.PulseGuard.heartbeat.PingUrls;
import com.viris.PulseGuard.monitor.dto.HeaderInput;
import com.viris.PulseGuard.monitor.dto.MonitorRequest;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import com.viris.PulseGuard.monitor.dto.MonitorSummaryResponse;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final PingUrls pingUrls;

    /** Length of the mini status bar on the monitors page. */
    static final int RECENT_CHECKS = 30;

    public MonitorService(MonitorRepository monitorRepository,
                          UserRepository userRepository,
                          PlanLimits planLimits,
                          SafeUrlValidator urlValidator,
                          SchedulerService schedulerService,
                          CheckRepository checkRepository,
                          PingUrls pingUrls) {
        this.monitorRepository = monitorRepository;
        this.userRepository = userRepository;
        this.planLimits = planLimits;
        this.urlValidator = urlValidator;
        this.schedulerService = schedulerService;
        this.checkRepository = checkRepository;
        this.pingUrls = pingUrls;
    }

    @Transactional
    public MonitorResponse createMonitor(Long userId, MonitorRequest request) {
        User user = requireUser(userId);
        Plan plan = user.getPlan();
        boolean heartbeat = request.typeOrDefault() == MonitorType.HEARTBEAT;

        // Heartbeats have no URL of ours to call, so no SSRF check and no polling cost: the
        // plan's minimum interval is about how often we poll, not how often their job runs.
        if (!heartbeat) {
            urlValidator.validate(request.url());
        }
        int maxMonitors = planLimits.maxMonitors(plan);
        if (monitorRepository.countByUserId(userId) >= maxMonitors) {
            throw PlanLimitExceededException.atMost(plan, "monitors", maxMonitors);
        }
        if (!heartbeat) {
            requireAllowedInterval(plan, request.intervalSeconds());
        }

        Monitor monitor = new Monitor();
        monitor.setUser(user);
        monitor.setType(request.typeOrDefault());
        if (heartbeat) {
            monitor.setHeartbeatToken(HeartbeatSchedule.newToken());
        }
        applyRequest(monitor, request);
        monitorRepository.save(monitor);
        // A heartbeat has nothing to schedule: the sweeper watches its deadline, which is set
        // by the first ping.
        if (!heartbeat) {
            schedulerService.schedule(monitor);
        }

        log.info("Created {} monitorId={} for userId={}", monitor.getType(), monitor.getId(), userId);
        return toResponse(monitor);
    }

    @Transactional(readOnly = true)
    public MonitorResponse getMonitor(Long userId, Long monitorId) {
        return toResponse(requireOwnedMonitor(userId, monitorId));
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
            return MonitorSummaryResponse.from(monitor, pingUrls.of(monitor), uptime24h,
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
        if (request.typeOrDefault() != monitor.getType()) {
            throw new InvalidMonitorException("type", "A monitor's type can't be changed. Create a new monitor instead.");
        }
        if (monitor.isHeartbeat()) {
            applyRequest(monitor, request);
            realignDeadline(monitor);
            log.info("Updated monitorId={} for userId={}", monitorId, userId);
            return toResponse(monitor);
        }
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
        return toResponse(monitor);
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
        if (monitor.isHeartbeat()) {
            // Nothing to (un)schedule. Resuming gives the job a full period from now, instead of
            // declaring it missed for the time it was paused.
            if (active && monitor.getLastCheckedAt() != null) {
                monitor.setPingDeadline(HeartbeatSchedule.deadlineAfter(Instant.now(), monitor));
            }
            log.info("{} monitorId={} for userId={}", active ? "Resumed" : "Paused", monitorId, userId);
            return toResponse(monitor);
        }
        // After the ownership check: another tenant's id has already become a 404 here.
        // Resume schedules from scratch, because pausing deleted the job.
        if (active) {
            schedulerService.schedule(monitor);
        } else {
            schedulerService.unschedule(monitorId);
        }
        log.info("{} monitorId={} for userId={}", active ? "Resumed" : "Paused", monitorId, userId);
        return toResponse(monitor);
    }

    private MonitorResponse toResponse(Monitor monitor) {
        return MonitorResponse.from(monitor, pingUrls.of(monitor), callerUsesApiKey());
    }

    /** Request bodies are write-only for API keys, like secret header values are for everyone. */
    private static boolean callerUsesApiKey() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof ApiKeyAuthenticationToken;
    }

    /**
     * After a heartbeat's schedule changes: the next deadline follows from the last ping. A
     * monitor that is already down is not re-declared missed for the same silence; its next
     * deadline moves ahead of now instead.
     */
    private static void realignDeadline(Monitor monitor) {
        if (monitor.getLastCheckedAt() == null) {
            return;
        }
        Instant deadline = HeartbeatSchedule.deadlineAfter(monitor.getLastCheckedAt(), monitor);
        Instant now = Instant.now();
        if (monitor.getState() == MonitorState.DOWN && !deadline.isAfter(now)) {
            deadline = HeartbeatSchedule.nextDeadlineAfterMiss(deadline, monitor, now);
        }
        monitor.setPingDeadline(deadline);
    }

    /** HTTP-only fields keep their defaults on a heartbeat (the columns are NOT NULL). */
    private void applyRequest(Monitor monitor, MonitorRequest request) {
        monitor.setName(request.name());
        monitor.setIntervalSeconds(request.intervalSeconds());
        if (monitor.isHeartbeat()) {
            monitor.setUrl("");
            monitor.setGraceSeconds(request.graceSeconds());
            return;
        }
        monitor.setUrl(request.url());
        monitor.setMethod(request.method());
        monitor.setExpectedStatuses(new ArrayList<>(request.statusesOrDefault()));
        monitor.setTimeoutMs(request.timeoutMs());
        // Absent keeps the saved body (an API key never sees it, so cannot send it back); blank
        // clears it. Only POST and PUT send one, so any other method drops it.
        String body = request.requestBody();
        boolean sendsBody = "POST".equals(request.method()) || "PUT".equals(request.method());
        if (!sendsBody || (body != null && body.isBlank())) {
            monitor.setRequestBody(null);
        } else if (body != null) {
            monitor.setRequestBody(body);
        }
        monitor.setHeaders(mergeHeaders(monitor.getHeaders(), request.headersOrEmpty()));
    }

    /**
     * The new header list. A header sent without a value keeps the value saved under the same
     * name: that is how an edit round-trips a secret the API never showed. With nothing saved
     * under that name, it is an error rather than an empty header.
     */
    private static List<MonitorHeader> mergeHeaders(List<MonitorHeader> saved, List<HeaderInput> requested) {
        Map<String, String> savedValues = saved.stream().collect(Collectors.toMap(
                h -> h.getName().toLowerCase(Locale.ROOT), MonitorHeader::getValue, (a, b) -> a));
        List<MonitorHeader> merged = new ArrayList<>(requested.size());
        for (HeaderInput header : requested) {
            String name = header.name().trim();
            String value = header.value();
            if (value == null) {
                value = savedValues.get(name.toLowerCase(Locale.ROOT));
                if (value == null) {
                    throw new InvalidMonitorException("headers", "Enter a value for the " + name + " header");
                }
            }
            merged.add(new MonitorHeader(name, value, merged.size()));
        }
        // A new list, not an edit of the old one: Hibernate then rewrites the rows cleanly.
        return merged;
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
