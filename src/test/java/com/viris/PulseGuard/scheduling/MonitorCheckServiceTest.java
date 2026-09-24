package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckExecutor;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorCheckServiceTest {

    private static final Long MONITOR_ID = 7L;

    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private CheckExecutor checkExecutor;
    @Mock
    private SchedulerService schedulerService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private MonitorCheckService service;
    private Monitor monitor;

    @BeforeEach
    void setUp() {
        service = new MonitorCheckService(monitorRepository, checkExecutor, schedulerService, meterRegistry);
        monitor = new Monitor();
        monitor.setId(MONITOR_ID);
        monitor.setActive(true);
    }

    @Test
    void runsCheckAndStampsLastCheckedAt() {
        Instant checkedAt = Instant.parse("2026-09-24T10:00:00Z");
        Check check = new Check();
        check.setResult(CheckResult.UP); // the executor always sets one
        check.setCheckedAt(checkedAt);
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
        when(checkExecutor.execute(monitor)).thenReturn(check);

        service.runCheck(MONITOR_ID);

        verify(checkExecutor).execute(monitor);
        verify(monitorRepository).touchLastCheckedAt(MONITOR_ID, checkedAt);
        verify(schedulerService, never()).unschedule(any());
    }

    @Test
    void countsCheckResultByOutcome() {
        Check check = new Check();
        check.setResult(CheckResult.DOWN);
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
        when(checkExecutor.execute(monitor)).thenReturn(check);

        service.runCheck(MONITOR_ID);

        assertThat(meterRegistry.get("pulseguard.checks").tag("result", "DOWN").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("pulseguard.checks").tag("result", "UP").counter()).isNull();
    }

    @Test
    void skippedChecksAreNotCounted() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        service.runCheck(MONITOR_ID);

        assertThat(meterRegistry.find("pulseguard.checks").counter()).isNull();
    }

    @Test
    void unschedulesOrphanedJobWhenMonitorNoLongerExists() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        service.runCheck(MONITOR_ID);

        verify(checkExecutor, never()).execute(any());
        verify(monitorRepository, never()).touchLastCheckedAt(any(), any());
        verify(schedulerService).unschedule(MONITOR_ID);
    }

    @Test
    void unschedulesStrayJobWhenMonitorIsPaused() {
        monitor.setActive(false);
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));

        service.runCheck(MONITOR_ID);

        verify(checkExecutor, never()).execute(any());
        verify(monitorRepository, never()).touchLastCheckedAt(any(), any());
        verify(schedulerService).unschedule(MONITOR_ID);
    }
}
