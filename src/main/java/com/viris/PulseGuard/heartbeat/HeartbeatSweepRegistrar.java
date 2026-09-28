package com.viris.PulseGuard.heartbeat;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
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

/**
 * Makes sure the sweep job exists, at every startup. Replacing it is harmless and picks up a
 * changed sweep interval. Its own group, so ScheduleReconciler (group "checks") never sees it.
 */
@Component
public class HeartbeatSweepRegistrar implements ApplicationRunner {

    public static final JobKey JOB_KEY = JobKey.jobKey("heartbeat-sweep", "heartbeats");

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSweepRegistrar.class);

    private final Scheduler scheduler;
    private final HeartbeatProperties properties;
    private final TransactionTemplate tx;

    public HeartbeatSweepRegistrar(Scheduler scheduler, HeartbeatProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.scheduler = scheduler;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        JobDetail job = JobBuilder.newJob(HeartbeatSweepJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(JOB_KEY.getName(), JOB_KEY.getGroup())
                .forJob(JOB_KEY)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(properties.sweepInterval().toMillis())
                        .repeatForever()
                        // After downtime, sweep once now; one sweep catches up on every deadline.
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
        // The JDBC job store writes through the app's DataSource, so it needs a transaction.
        tx.executeWithoutResult(status -> {
            try {
                scheduler.scheduleJob(job, Set.of(trigger), true);
            } catch (SchedulerException e) {
                throw new IllegalStateException("Could not schedule the heartbeat sweep", e);
            }
        });
        log.info("Heartbeat sweep scheduled every {}", properties.sweepInterval());
    }
}
