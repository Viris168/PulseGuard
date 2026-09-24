package com.viris.PulseGuard.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckJobTest {

    @Mock
    private MonitorCheckService monitorCheckService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private JobExecutionContext contextWith(String monitorId) {
        JobDataMap data = new JobDataMap();
        data.put(CheckJob.MONITOR_ID_KEY, monitorId);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data);
        return context;
    }

    @Test
    void recordsSchedulerLagOncePerFire() {
        JobExecutionContext context = contextWith("42");
        when(context.getScheduledFireTime()).thenReturn(Date.from(Instant.now().minusSeconds(2)));

        new CheckJob(monitorCheckService, meterRegistry).execute(context);

        Timer lag = meterRegistry.get("pulseguard.scheduler.lag").timer();
        assertThat(lag.count()).isEqualTo(1);
        assertThat(lag.totalTime(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void stillRunsCheckWhenScheduledFireTimeIsMissing() {
        // Mock default: getScheduledFireTime() returns null.
        new CheckJob(monitorCheckService, meterRegistry).execute(contextWith("42"));

        verify(monitorCheckService).runCheck(42L);
        assertThat(meterRegistry.get("pulseguard.scheduler.lag").timer().count()).isZero();
    }

    @Test
    void runsCheckForMonitorIdFromJobData() {
        new CheckJob(monitorCheckService, meterRegistry).execute(contextWith("42"));

        verify(monitorCheckService).runCheck(42L);
    }

    @Test
    void swallowsFailureSoQuartzDoesNotRefireImmediately() {
        doThrow(new IllegalStateException("database down")).when(monitorCheckService).runCheck(42L);

        assertThatCode(() -> new CheckJob(monitorCheckService, meterRegistry).execute(contextWith("42")))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsJobWhoseMonitorIdIsMissingOrMalformed() {
        for (String bad : new String[]{null, "", "not-a-number"}) {
            JobExecutionContext context = contextWith(bad);
            JobDetail detail = mock(JobDetail.class);
            when(detail.getKey()).thenReturn(JobKey.jobKey("monitor-x", "checks"));
            when(context.getJobDetail()).thenReturn(detail);

            assertThatCode(() -> new CheckJob(monitorCheckService, meterRegistry).execute(context))
                    .doesNotThrowAnyException();
        }
        verify(monitorCheckService, never()).runCheck(any());
    }
}
