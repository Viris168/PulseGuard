package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup repair: the monitors table is the source of truth, and Quartz is corrected to
 * match it — active monitors without a job get one, jobs without an active monitor go.
 */
@Component
public class ScheduleReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReconciler.class);

    private final MonitorRepository monitorRepository;
    private final SchedulerService schedulerService;
    private final SchedulingProperties properties;

    public ScheduleReconciler(MonitorRepository monitorRepository,
                              SchedulerService schedulerService,
                              SchedulingProperties properties) {
        this.monitorRepository = monitorRepository;
        this.schedulerService = schedulerService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.reconcileOnStartup()) {
            return;
        }
        reconcile();
    }

    void reconcile() {
        // 1. What SHOULD exist. Keep the Monitor objects: scheduling needs the real interval.
        List<Monitor> activeMonitors = monitorRepository.findAllByActiveTrue();
        Set<Long> activeIds = activeMonitors.stream()
                .map(Monitor::getId)
                .collect(Collectors.toSet());

        // 2. What DOES exist: ids with a Quartz job
        Set<Long> scheduledIds = schedulerService.scheduledMonitorIds();

        // 3. Active but not scheduled → schedule
        int added = 0;
        for (Monitor monitor : activeMonitors) {
            if (!scheduledIds.contains(monitor.getId())) {
                schedulerService.schedule(monitor);
                added++;
            }
        }

        // 4. Scheduled but not active (paused OR deleted) → unschedule
        int removed = 0;
        for (Long id : scheduledIds) {
            if (!activeIds.contains(id)) {
                schedulerService.unschedule(id);
                removed++;
            }
        }

        log.info("Reconciled schedules: {} added, {} removed", added, removed);
    }
}