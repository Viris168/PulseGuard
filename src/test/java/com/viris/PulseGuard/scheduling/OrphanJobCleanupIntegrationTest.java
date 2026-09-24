package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.check.CheckRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job whose monitor no longer exists (e.g. removed by a user-delete cascade) must delete
 * itself on its next fire — from inside its own execution, through the real job store.
 */
@SpringBootTest
class OrphanJobCleanupIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    private static final Long MISSING_MONITOR_ID = 999_999L;
    private static final JobKey JOB_KEY = SchedulerService.jobKey(MISSING_MONITOR_ID);

    @Autowired
    Scheduler scheduler;
    @Autowired
    CheckRepository checks;

    @AfterEach
    void stopScheduler() throws Exception {
        scheduler.standby();
        scheduler.deleteJob(JOB_KEY);
    }

    @Test
    void orphanedJobUnschedulesItselfOnFirstFire() throws Exception {
        JobDetail job = JobBuilder.newJob(CheckJob.class)
                .withIdentity(JOB_KEY)
                .usingJobData(CheckJob.MONITOR_ID_KEY, MISSING_MONITOR_ID.toString())
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(job)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(60))
                .build();
        scheduler.scheduleJob(job, trigger);
        assertThat(scheduler.checkExists(JOB_KEY)).isTrue();

        scheduler.start();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (scheduler.checkExists(JOB_KEY) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(scheduler.checkExists(JOB_KEY)).as("orphaned job removed").isFalse();
        assertThat(checks.count()).as("no check ran for a missing monitor").isZero();
    }
}
