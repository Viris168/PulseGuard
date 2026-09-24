package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.InvalidMonitorUrlException;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.common.exception.PlanLimitExceededException;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.dto.MonitorRequest;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long MONITOR_ID = 3L;

    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SafeUrlValidator urlValidator;
    @Mock
    private SchedulerService schedulerService;

    private MonitorService service;
    private User owner;

    @BeforeEach
    void setUp() {
        // PlanLimits is a pure lookup — exercise the real limits, not a stub.
        service = new MonitorService(monitorRepository, userRepository, new PlanLimits(), urlValidator,
                schedulerService);
        owner = new User();
        owner.setEmail("owner@example.com");
        owner.setPlan(Plan.PRO);
    }

    private MonitorRequest request(String url, int intervalSeconds) {
        return new MonitorRequest("API health", url, "GET", 200, intervalSeconds, 5000);
    }

    private MonitorRequest request() {
        return request("https://example.com/health", 60);
    }

    private Monitor ownedMonitor() {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("old name");
        monitor.setUrl("https://example.com/old");
        monitor.setIntervalSeconds(300);
        return monitor;
    }

    // --- create ---------------------------------------------------------

    @Test
    void createSavesMonitorOwnedByTheRequestingUser() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(monitorRepository.countByUserId(OWNER_ID)).thenReturn(0L);

        MonitorResponse response = service.createMonitor(OWNER_ID, request());

        verify(monitorRepository).save(any(Monitor.class));
        assertThat(response.name()).isEqualTo("API health");
        assertThat(response.url()).isEqualTo("https://example.com/health");
    }

    @Test
    void createSchedulesTheNewMonitor() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(monitorRepository.countByUserId(OWNER_ID)).thenReturn(0L);

        service.createMonitor(OWNER_ID, request());

        verify(schedulerService).schedule(any(Monitor.class));
    }

    @Test
    void createRejectsUrlThatFailsSsrfValidation() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        doThrow(new InvalidMonitorUrlException("host resolves to a private or reserved address"))
                .when(urlValidator).validate(anyString());

        assertThatThrownBy(() -> service.createMonitor(OWNER_ID, request("http://169.254.169.254/", 60)))
                .isInstanceOf(InvalidMonitorUrlException.class);

        verify(monitorRepository, never()).save(any());
    }

    @Test
    void createRejectsWhenPlanMonitorCapIsReached() {
        owner.setPlan(Plan.FREE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(monitorRepository.countByUserId(OWNER_ID)).thenReturn(3L); // FREE allows 3

        assertThatThrownBy(() -> service.createMonitor(OWNER_ID, request("https://example.com/health", 300)))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("at most 3 monitors");

        verify(monitorRepository, never()).save(any());
    }

    @Test
    void createRejectsIntervalBelowThePlanMinimum() {
        owner.setPlan(Plan.FREE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(monitorRepository.countByUserId(OWNER_ID)).thenReturn(0L);

        // 60s passes DTO validation but FREE requires 300s.
        assertThatThrownBy(() -> service.createMonitor(OWNER_ID, request("https://example.com/health", 60)))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("at least 300");

        verify(monitorRepository, never()).save(any());
    }

    @Test
    void createAllowsBusinessPlanPastTheProCap() {
        owner.setPlan(Plan.BUSINESS);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(monitorRepository.countByUserId(OWNER_ID)).thenReturn(500L);

        assertThatCode(() -> service.createMonitor(OWNER_ID, request())).doesNotThrowAnyException();
    }

    // --- read -----------------------------------------------------------

    @Test
    void getScopesLookupToTheOwner() {
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        assertThat(service.getMonitor(OWNER_ID, MONITOR_ID).name()).isEqualTo("old name");
    }

    @Test
    void getThrowsNotFoundForAnotherUsersMonitor() {
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMonitor(OWNER_ID, MONITOR_ID))
                .isInstanceOf(MonitorNotFoundException.class);
    }

    @Test
    void listReturnsOnlyTheOwnersMonitors() {
        when(monitorRepository.findAllByUserId(OWNER_ID)).thenReturn(List.of(ownedMonitor()));

        assertThat(service.listMonitors(OWNER_ID)).hasSize(1);
        verify(monitorRepository, never()).findAll();
    }

    // --- update ---------------------------------------------------------

    @Test
    void updateScopesLookupToTheOwner() {
        // Regression: the lookup once passed (userId, monitorId), which matched a
        // different tenant's monitor and let this call overwrite it.
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request());

        verify(monitorRepository).findByIdAndUserId(MONITOR_ID, OWNER_ID);
        verify(monitorRepository, never()).findByIdAndUserId(OWNER_ID, MONITOR_ID);
    }

    @Test
    void updateThrowsNotFoundForAnotherUsersMonitor() {
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMonitor(OWNER_ID, MONITOR_ID, request()))
                .isInstanceOf(MonitorNotFoundException.class);
    }

    @Test
    void updatePreservesStateAndFailureCounters() {
        Monitor monitor = ownedMonitor();
        monitor.setState(MonitorState.DOWN);
        monitor.setConsecutiveFailures(3);
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request());

        // Renaming a monitor must not resolve it; only IncidentEngine moves the state machine.
        assertThat(monitor.getState()).isEqualTo(MonitorState.DOWN);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(3);
        assertThat(monitor.getName()).isEqualTo("API health");
    }

    @Test
    void updateKeepsTheOriginalOwner() {
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request());

        assertThat(monitor.getUser()).isSameAs(owner);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateValidatesAChangedUrl() {
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));
        doThrow(new InvalidMonitorUrlException("host resolves to a private or reserved address"))
                .when(urlValidator).validate("http://127.0.0.1/");

        assertThatThrownBy(() -> service.updateMonitor(OWNER_ID, MONITOR_ID, request("http://127.0.0.1/", 60)))
                .isInstanceOf(InvalidMonitorUrlException.class);
    }

    @Test
    void updateSkipsValidationWhenTheUrlIsUnchanged() {
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request(monitor.getUrl(), 60));

        verify(urlValidator, never()).validate(anyString());
    }

    @Test
    void updateRejectsIntervalBelowThePlanMinimum() {
        owner.setPlan(Plan.FREE);
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        assertThatThrownBy(() -> service.updateMonitor(OWNER_ID, MONITOR_ID, request(monitor.getUrl(), 60)))
                .isInstanceOf(PlanLimitExceededException.class);

        assertThat(monitor.getIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void updateReschedulesWhenIntervalChanges() {
        Monitor monitor = ownedMonitor(); // 300s
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request()); // 60s

        verify(schedulerService).reschedule(monitor);
    }

    @Test
    void updateDoesNotRescheduleWhenOnlyNameChanges() {
        Monitor monitor = ownedMonitor(); // 300s
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request("https://example.com/old", 300));

        verify(schedulerService, never()).reschedule(any());
    }

    @Test
    void updateDoesNotRescheduleAPausedMonitor() {
        // Rescheduling would recreate the job via its fallback and un-pause the checks.
        Monitor monitor = ownedMonitor();
        monitor.setActive(false);
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.updateMonitor(OWNER_ID, MONITOR_ID, request());

        verify(schedulerService, never()).reschedule(any());
        verify(schedulerService, never()).schedule(any());
    }

    // --- pause / resume -------------------------------------------------

    @Test
    void pauseDeactivatesWithoutTouchingState() {
        Monitor monitor = ownedMonitor();
        monitor.setState(MonitorState.DOWN);
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        assertThat(service.pauseMonitor(OWNER_ID, MONITOR_ID).isActive()).isFalse();
        assertThat(monitor.getState()).isEqualTo(MonitorState.DOWN);
        verify(schedulerService).unschedule(MONITOR_ID);
    }

    @Test
    void resumeReactivates() {
        Monitor monitor = ownedMonitor();
        monitor.setActive(false);
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        assertThat(service.resumeMonitor(OWNER_ID, MONITOR_ID).isActive()).isTrue();
        verify(schedulerService).schedule(monitor);
    }

    @Test
    void pauseThrowsNotFoundForAnotherUsersMonitor() {
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pauseMonitor(OWNER_ID, MONITOR_ID))
                .isInstanceOf(MonitorNotFoundException.class);
        verify(schedulerService, never()).unschedule(any());
    }

    @Test
    void resumeOfAnotherUsersMonitorDoesNotSchedule() {
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resumeMonitor(OWNER_ID, MONITOR_ID))
                .isInstanceOf(MonitorNotFoundException.class);
        verify(schedulerService, never()).schedule(any());
    }

    // --- delete ---------------------------------------------------------

    @Test
    void deleteRemovesTheOwnersMonitor() {
        Monitor monitor = ownedMonitor();
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.of(monitor));

        service.deleteMonitor(OWNER_ID, MONITOR_ID);

        verify(monitorRepository).delete(monitor);
        verify(schedulerService).unschedule(MONITOR_ID);
    }

    @Test
    void deleteThrowsNotFoundForAnotherUsersMonitor() {
        when(monitorRepository.findByIdAndUserId(MONITOR_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMonitor(OWNER_ID, MONITOR_ID))
                .isInstanceOf(MonitorNotFoundException.class);

        verify(monitorRepository, never()).delete(any());
        // One tenant must never be able to stop another tenant's checks.
        verify(schedulerService, never()).unschedule(any());
    }
}
