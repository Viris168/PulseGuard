package com.viris.PulseGuard.stats;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.TimeZone;

/**
 * Makes the nightly job match the settings at every startup: scheduled (replacing any old
 * cron) when enabled, removed when not. Its own group, so ScheduleReconciler never touches it.
 */
@Component
public class HousekeepingRegistrar implements ApplicationRunner {

    public static final JobKey JOB_KEY = JobKey.jobKey("housekeeping", "maintenance");

    private static final Logger log = LoggerFactory.getLogger(HousekeepingRegistrar.class);

    private final Scheduler scheduler;
    private final HousekeepingProperties properties;
    private final TransactionTemplate tx;

    public HousekeepingRegistrar(Scheduler scheduler, HousekeepingProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.scheduler = scheduler;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        // The JDBC job store writes through the app's DataSource, so it needs a transaction.
        tx.executeWithoutResult(status -> {
            try {
                if (!properties.enabled()) {
                    if (scheduler.deleteJob(JOB_KEY)) {
                        log.info("Housekeeping disabled; nightly job removed");
                    }
                    return;
                }
                JobDetail job = JobBuilder.newJob(HousekeepingJob.class)
                        .withIdentity(JOB_KEY)
                        .storeDurably()
                        .build();
                Trigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity(JOB_KEY.getName(), JOB_KEY.getGroup())
                        .forJob(JOB_KEY)
                        .withSchedule(CronScheduleBuilder.cronSchedule(properties.cron())
                                .inTimeZone(TimeZone.getTimeZone("UTC"))
                                // After downtime over the scheduled time, run once as soon as possible.
                                .withMisfireHandlingInstructionFireAndProceed())
                        .build();
                scheduler.scheduleJob(job, Set.of(trigger), true);
            } catch (SchedulerException e) {
                throw new IllegalStateException("Could not schedule housekeeping", e);
            }
        });
        if (properties.enabled()) {
            log.info("Housekeeping scheduled: '{}' UTC, raw history {} days", properties.cron(), properties.rawCheckDays());
        }
    }
}
