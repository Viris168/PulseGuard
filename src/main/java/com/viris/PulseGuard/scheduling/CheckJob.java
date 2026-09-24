package com.viris.PulseGuard.scheduling;


import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * One Quartz job per active monitor; each fire runs a single check.
 *
 * <p>Instantiated per fire by Boot's {@code SpringBeanJobFactory}, which resolves the
 * constructor like any other bean. {@code @DisallowConcurrentExecution} is per job key, so a
 * slow check delays the next run of its own monitor and never overlaps it; other monitors
 * are unaffected.
 *
 * <p>This job never throws. A {@code JobExecutionException} can ask Quartz to re-fire
 * immediately, which turns a failing dependency into a hot loop; the next scheduled fire is
 * the retry.
 */
@DisallowConcurrentExecution
public class CheckJob implements Job {

    /** JobDataMap key. The value is a string because the job store runs with useProperties. */
    public static final String MONITOR_ID_KEY = "monitorId";

    private static final Logger log = LoggerFactory.getLogger(CheckJob.class);

    private final MonitorCheckService monitorCheckService;
    private final Timer schedulerLag;

    public CheckJob(MonitorCheckService monitorCheckService, MeterRegistry meterRegistry) {
        this.monitorCheckService = monitorCheckService;
        this.schedulerLag = Timer.builder("pulseguard.scheduler.lag")
                .description("Delay between a check's scheduled and actual start")
                .register(meterRegistry);
    }

    @Override
    public void execute(JobExecutionContext context) {
        // Guarded: a metric must never stop the check it measures.
        Date scheduledAt = context.getScheduledFireTime();
        if (scheduledAt != null) {
            schedulerLag.record(Duration.between(scheduledAt.toInstant(), Instant.now()));
        }

        String rawId = context.getMergedJobDataMap().getString(MONITOR_ID_KEY);
        Long monitorId;
        try {
            monitorId = Long.valueOf(rawId);
        } catch (NumberFormatException e) {
            log.error("Check job {} has no valid {} (got '{}'); skipping",
                    context.getJobDetail().getKey(), MONITOR_ID_KEY, rawId);
            return;
        }

        try {
            monitorCheckService.runCheck(monitorId);
        } catch (Exception e) {
            log.error("Check job failed for monitorId={}", monitorId, e);
        }
    }
}
