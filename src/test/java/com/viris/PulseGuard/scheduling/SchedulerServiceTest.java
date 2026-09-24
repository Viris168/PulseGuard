package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.monitor.Monitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;

import java.time.Duration;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    private static final Long MONITOR_ID = 42L;
    private static final JobKey JOB_KEY = JobKey.jobKey("monitor-42", "checks");

    @Mock
    private Scheduler scheduler;
    @Captor
    private ArgumentCaptor<JobDetail> jobCaptor;
    @Captor
    private ArgumentCaptor<Set<? extends Trigger>> triggersCaptor;

    private SchedulerService service;

    @BeforeEach
    void setUp() {
        // Jitter off, so start times are predictable; the jitter test builds its own service.
        service = new SchedulerService(scheduler, new SchedulingProperties(Duration.ZERO, false));
    }

    private Monitor monitor(long id, int intervalSeconds) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setIntervalSeconds(intervalSeconds);
        return monitor;
    }

    @Test
    void scheduleCreatesDurableJobCarryingMonitorIdAsString() throws Exception {
        service.schedule(monitor(MONITOR_ID, 60));

        verify(scheduler).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));
        JobDetail job = jobCaptor.getValue();
        assertThat(job.getKey()).isEqualTo(JOB_KEY);
        assertThat(job.getJobClass()).isEqualTo(CheckJob.class);
        assertThat(job.isDurable()).isTrue();
        assertThat(job.getJobDataMap().getString(CheckJob.MONITOR_ID_KEY)).isEqualTo("42");
    }

    @Test
    void scheduleUsesMonitorIntervalRepeatingForeverWithOneCatchUpRun() throws Exception {
        service.schedule(monitor(MONITOR_ID, 60));

        verify(scheduler).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));
        assertThat(triggersCaptor.getValue()).hasSize(1);
        SimpleTrigger trigger = (SimpleTrigger) triggersCaptor.getValue().iterator().next();
        assertThat(trigger.getKey()).isEqualTo(SchedulerService.triggerKey(MONITOR_ID));
        assertThat(trigger.getJobKey()).isEqualTo(JOB_KEY);
        assertThat(trigger.getRepeatInterval()).isEqualTo(60_000L); // milliseconds
        assertThat(trigger.getRepeatCount()).isEqualTo(SimpleTrigger.REPEAT_INDEFINITELY);
        assertThat(trigger.getMisfireInstruction())
                .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT);
    }

    @Test
    void scheduleWrapsQuartzFailureInUncheckedException() throws Exception {
        Monitor monitor = monitor(MONITOR_ID, 60);
        // scheduleJob(job, triggers, replace) returns void, so it's doThrow().when(), not when().thenThrow().
        doThrow(new SchedulerException("db down"))
                .when(scheduler).scheduleJob(any(JobDetail.class), anySet(), eq(true));

        assertThatThrownBy(() -> service.schedule(monitor))
                .isInstanceOf(CheckSchedulingException.class)
                .hasMessageContaining("monitorId=42");
    }

    @Test
    void firstFireIsJitteredButNeverLaterThanOneInterval() throws Exception {
        // Jitter (30s) is larger than the interval (10s), so the interval is the cap.
        SchedulerService jittered = new SchedulerService(scheduler,
                new SchedulingProperties(Duration.ofSeconds(30), false));
        long before = System.currentTimeMillis();

        jittered.schedule(monitor(MONITOR_ID, 10));

        long after = System.currentTimeMillis();
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));
        long startAt = triggersCaptor.getValue().iterator().next().getStartTime().getTime();
        // Random, so assert a range rather than an exact time.
        assertThat(startAt).isBetween(before, after + 10_000);
    }

    @Test
    void rescheduleSwapsTriggerForSameJob() throws Exception {
        ArgumentCaptor<Trigger> newTrigger = ArgumentCaptor.forClass(Trigger.class);
        when(scheduler.rescheduleJob(eq(SchedulerService.triggerKey(MONITOR_ID)), newTrigger.capture()))
                .thenReturn(new Date());

        service.reschedule(monitor(MONITOR_ID, 300));

        SimpleTrigger trigger = (SimpleTrigger) newTrigger.getValue();
        assertThat(trigger.getJobKey()).isEqualTo(JOB_KEY);
        assertThat(trigger.getRepeatInterval()).isEqualTo(300_000L);
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), anySet(), anyBoolean());
    }

    @Test
    void rescheduleFallsBackToScheduleWhenNoTrigger() throws Exception {
        // null = Quartz found no old trigger to replace.
        when(scheduler.rescheduleJob(eq(SchedulerService.triggerKey(MONITOR_ID)), any(Trigger.class)))
                .thenReturn(null);

        service.reschedule(monitor(MONITOR_ID, 300));

        verify(scheduler).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));
        assertThat(jobCaptor.getValue().getKey()).isEqualTo(JOB_KEY);
    }

    @Test
    void isScheduledIsTrueWhenQuartzHasTheJob() throws Exception {
        when(scheduler.checkExists(JOB_KEY)).thenReturn(true);

        assertThat(service.isScheduled(MONITOR_ID)).isTrue();
    }

    @Test
    void isScheduledIsFalseWhenQuartzHasNoJob() throws Exception {
        when(scheduler.checkExists(JOB_KEY)).thenReturn(false);

        assertThat(service.isScheduled(MONITOR_ID)).isFalse();
    }

    @Test
    void scheduledMonitorIdsParsesIdsFromChecksGroup() throws Exception {
        when(scheduler.getJobKeys(GroupMatcher.jobGroupEquals("checks")))
                .thenReturn(Set.of(JobKey.jobKey("monitor-1", "checks"), JobKey.jobKey("monitor-2", "checks")));

        assertThat(service.scheduledMonitorIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void keysJobsByMonitorIdInChecksGroup() {
        assertThat(SchedulerService.jobKey(MONITOR_ID)).isEqualTo(JOB_KEY);
    }

    @Test
    void unscheduleDeletesTheMonitorsJob() throws Exception {
        when(scheduler.deleteJob(JOB_KEY)).thenReturn(true);

        service.unschedule(MONITOR_ID);

        verify(scheduler).deleteJob(JOB_KEY);
    }

    @Test
    void unscheduleIsANoOpWhenThereIsNoJob() throws Exception {
        when(scheduler.deleteJob(JOB_KEY)).thenReturn(false);

        assertThatCode(() -> service.unschedule(MONITOR_ID)).doesNotThrowAnyException();
    }

    @Test
    void unscheduleWrapsQuartzFailureInUncheckedException() throws Exception {
        when(scheduler.deleteJob(JOB_KEY)).thenThrow(new SchedulerException("db down"));

        assertThatThrownBy(() -> service.unschedule(MONITOR_ID))
                .isInstanceOf(CheckSchedulingException.class)
                .hasMessageContaining("monitorId=42")
                .hasCauseInstanceOf(SchedulerException.class);
    }
}
