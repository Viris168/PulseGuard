package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.monitor.Monitor;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * The only class that talks to the Quartz {@link Scheduler}. One durable job per active
 * monitor, keyed {@code monitor-{id}} in group {@code checks}.
 *
 * <p>Every method is {@code @Transactional}: the JDBC job store writes through the app's
 * DataSource, so called from a monitor change it joins that transaction and commits or
 * rolls back with the monitor row; called from a job, it gets a transaction of its own.
 */
@Service
public class SchedulerService {

    public static final String JOB_GROUP = "checks";

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final Scheduler scheduler;
    private final SchedulingProperties properties;

    public SchedulerService(Scheduler scheduler, SchedulingProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }


    @Transactional
    public void schedule(Monitor monitor) {
        JobDetail job = buildJob(monitor);
        // Step 2: build the trigger
        Trigger trigger = buildTrigger(monitor);
        // Step 3: hand both to Quartz
        try {
            // true = replace if it already exists, so calling schedule twice is safe
            scheduler.scheduleJob(job, Set.of(trigger), true);
            log.info("Scheduled checks every {}s for monitorId={}",
                    monitor.getIntervalSeconds(), monitor.getId());
        } catch (SchedulerException e) {
            throw new CheckSchedulingException("Could not schedule monitorId=" + monitor.getId(), e);
        }
    }

    @Transactional
    public void unschedule(Long monitorId) {
        try {
            if (scheduler.deleteJob(jobKey(monitorId))) {
                log.info("Unscheduled checks for monitorId={}", monitorId);
            }
        } catch (SchedulerException e) {
            throw new CheckSchedulingException("Could not unschedule monitorId=" + monitorId, e);
        }
    }

    @Transactional
    public void reschedule(Monitor monitor) {
        try {
            Date nextFire = scheduler.rescheduleJob(triggerKey(monitor.getId()), buildTrigger(monitor));
            if (nextFire == null) {
                // No old trigger to replace; create the job and trigger from scratch.
                schedule(monitor);
                return;
            }
            log.info("Rescheduled checks every {}s for monitorId={}",
                    monitor.getIntervalSeconds(), monitor.getId());
        } catch (SchedulerException e) {
            throw new CheckSchedulingException("Could not reschedule monitorId=" + monitor.getId(), e);
        }
    }

    @Transactional(readOnly = true)
    public boolean isScheduled(Long monitorId) {
        try {
            return scheduler.checkExists(jobKey(monitorId));
        } catch (SchedulerException e) {
            throw new CheckSchedulingException("Could not look up monitorId=" + monitorId, e);
        }
    }

    /** Monitor ids that currently have a check job in Quartz. */
    @Transactional(readOnly = true)
    public Set<Long> scheduledMonitorIds() {
        try {
            return scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP)).stream()
                    .map(key -> Long.valueOf(key.getName().substring("monitor-".length())))
                    .collect(Collectors.toSet());
        } catch (SchedulerException e) {
            throw new CheckSchedulingException("Could not list scheduled monitors", e);
        }
    }

    public static TriggerKey triggerKey(Long monitorId) {
        return TriggerKey.triggerKey("monitor-" + monitorId, JOB_GROUP);
    }

    public static JobKey jobKey(Long monitorId) {
        return JobKey.jobKey("monitor-" + monitorId, JOB_GROUP);
    }

    //Without jitter:  |||||||||| ........ |||||||||| ........   (all at once)
    //With jitter:     | || | ||| | || | | | || | ||| | || | |   (spread out)

    private Date firstFireTime(Monitor monitor) {
        long intervalMs = monitor.getIntervalSeconds() * 1000L;
        long maxDelayMs = Math.min(properties.jitter().toMillis(), intervalMs);
        long delayMs = ThreadLocalRandom.current().nextLong(0, maxDelayMs + 1);
        return new Date(System.currentTimeMillis() + delayMs);
    }

    private JobDetail buildJob(Monitor monitor) {
        return JobBuilder.newJob(CheckJob.class)
                .withIdentity(jobKey(monitor.getId()))
                .usingJobData(CheckJob.MONITOR_ID_KEY, monitor.getId().toString())
                .storeDurably()
                .build();
    }

    private Trigger buildTrigger(Monitor monitor) {
        return TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(monitor.getId()))      // the name tag
                .forJob(jobKey(monitor.getId()))                // which job this alarm runs
                .startAt(firstFireTime(monitor)) // first run: random delay to spread load
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(monitor.getIntervalSeconds()) // every N seconds
                        .repeatForever()                                     // never stop
                        .withMisfireHandlingInstructionNowWithExistingCount()) // after downtime: run once now
                .build();
    }
}
