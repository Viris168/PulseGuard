package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleReconcilerTest {

    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private SchedulerService schedulerService;

    private ScheduleReconciler reconciler(boolean enabled) {
        return new ScheduleReconciler(monitorRepository, schedulerService,
                new SchedulingProperties(Duration.ZERO, enabled));
    }

    private Monitor activeMonitor(long id) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setActive(true);
        return monitor;
    }

    @Test
    void schedulesActiveMonitorsThatHaveNoJob() {
        Monitor one = activeMonitor(1);
        Monitor two = activeMonitor(2);
        when(monitorRepository.findAllByActiveTrue()).thenReturn(List.of(one, two));
        when(schedulerService.scheduledMonitorIds()).thenReturn(Set.of(1L));

        reconciler(true).run(null);

        verify(schedulerService).schedule(two);
        verify(schedulerService, never()).schedule(one);
        verify(schedulerService, never()).unschedule(any());
    }

    @Test
    void unschedulesJobsWhoseMonitorIsPausedOrDeleted() {
        // 9 is not in the active list: to the reconciler, paused and deleted look the same.
        when(monitorRepository.findAllByActiveTrue()).thenReturn(List.of(activeMonitor(1)));
        when(schedulerService.scheduledMonitorIds()).thenReturn(Set.of(1L, 9L));

        reconciler(true).run(null);

        verify(schedulerService).unschedule(9L);
        verify(schedulerService, never()).unschedule(1L);
        verify(schedulerService, never()).schedule(any());
    }

    @Test
    void doesNothingWhenDisabled() {
        reconciler(false).run(null);

        verifyNoInteractions(monitorRepository, schedulerService);
    }
}
