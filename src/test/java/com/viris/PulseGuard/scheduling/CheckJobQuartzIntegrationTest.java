package com.viris.PulseGuard.scheduling;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Fires a {@link CheckJob} through the real clustered JDBC job store, proving what the unit
 * test cannot: Boot's job factory builds the job through its constructor, and the string
 * monitor id survives a round trip through {@code qrtz_job_details}.
 */
@SpringBootTest
class CheckJobQuartzIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    private static final JobKey JOB_KEY = JobKey.jobKey("monitor-42", "checks");

    @Autowired
    Scheduler scheduler;

    @MockitoBean
    MonitorCheckService monitorCheckService;

    @AfterEach
    void stopScheduler() throws Exception {
        scheduler.standby();
        scheduler.deleteJob(JOB_KEY);
    }

    @Test
    void firesCheckJobWithInjectedServiceAndMonitorIdFromJobStore() throws Exception {
        JobDetail job = JobBuilder.newJob(CheckJob.class)
                .withIdentity(JOB_KEY)
                .usingJobData(CheckJob.MONITOR_ID_KEY, "42")
                .storeDurably()
                .build();
        // Repeating like production triggers: a one-shot trigger is removed by Quartz as it
        // completes, which races the deleteJob in cleanup.
        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(job)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(60))
                .build();

        scheduler.scheduleJob(job, trigger);
        scheduler.start();

        verify(monitorCheckService, timeout(10_000)).runCheck(42L);
    }
}
