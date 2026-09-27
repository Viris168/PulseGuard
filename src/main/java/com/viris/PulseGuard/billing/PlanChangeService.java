package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an account to a new plan and brings what it already has within the new limits.
 *
 * <p>On a downgrade, monitors checking faster than the plan allows are slowed to its minimum,
 * and channels the plan no longer includes are switched off (kept, so an upgrade can turn them
 * back on). Monitors over the count limit keep running; the user just cannot add more until
 * they are under it, which is what the billing page tells them before they downgrade.
 */
@Service
public class PlanChangeService {

    private static final Logger log = LoggerFactory.getLogger(PlanChangeService.class);

    private final UserRepository userRepository;
    private final MonitorRepository monitorRepository;
    private final NotificationChannelRepository channelRepository;
    private final SchedulerService schedulerService;
    private final PlanLimits planLimits;

    public PlanChangeService(UserRepository userRepository,
                             MonitorRepository monitorRepository,
                             NotificationChannelRepository channelRepository,
                             SchedulerService schedulerService,
                             PlanLimits planLimits) {
        this.userRepository = userRepository;
        this.monitorRepository = monitorRepository;
        this.channelRepository = channelRepository;
        this.schedulerService = schedulerService;
        this.planLimits = planLimits;
    }

    /**
     * Joins the caller's transaction (the webhook's), so the plan, the monitor edits and the
     * Quartz triggers (same datasource) all commit or roll back with the processed event.
     *
     * @return false if the account was already on {@code newPlan}
     */
    @Transactional
    public boolean applyPlan(Long userId, Plan newPlan) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " no longer exists"));
        Plan oldPlan = user.getPlan();
        if (oldPlan == newPlan) {
            return false;
        }
        user.setPlan(newPlan);

        // Direction-free on purpose: on an upgrade both steps simply find nothing to change.
        int slowed = slowDownMonitors(userId, planLimits.minIntervalSeconds(newPlan));
        int disabled = disableChannels(userId, newPlan);

        log.info("Plan changed for userId={}: {} -> {} ({} monitors slowed, {} channels disabled)",
                userId, oldPlan, newPlan, slowed, disabled);
        return true;
    }

    private int slowDownMonitors(Long userId, int minIntervalSeconds) {
        var monitors = monitorRepository.findAllByUserIdAndIntervalSecondsLessThan(userId, minIntervalSeconds);
        for (Monitor monitor : monitors) {
            // Entity edit, not a bulk update: @Version must catch a check saving the same row.
            monitor.setIntervalSeconds(minIntervalSeconds);
            // A paused monitor has no job; resume schedules it with the new interval.
            if (monitor.isActive()) {
                schedulerService.reschedule(monitor);
            }
            log.info("Slowed monitorId={} to every {}s for userId={}", monitor.getId(), minIntervalSeconds, userId);
        }
        return monitors.size();
    }

    private int disableChannels(Long userId, Plan plan) {
        int disabled = 0;
        for (NotificationChannel channel : channelRepository.findAllByUserIdAndEnabledTrue(userId)) {
            if (!planLimits.allowsChannel(plan, channel.getType())) {
                channel.setEnabled(false);
                disabled++;
                log.info("Disabled {} channelId={} for userId={}: not on the {} plan",
                        channel.getType(), channel.getId(), userId, plan);
            }
        }
        return disabled;
    }
}
