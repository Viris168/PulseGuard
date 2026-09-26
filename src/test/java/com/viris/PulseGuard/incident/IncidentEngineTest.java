package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.incident.dto.IncidentOpenedEvent;
import com.viris.PulseGuard.incident.dto.IncidentProperties;
import com.viris.PulseGuard.incident.dto.IncidentResolvedEvent;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentEngineTest {

    private static final Long MONITOR_ID = 42L;
    private static final Instant CHECKED_AT = Instant.parse("2026-09-26T10:00:00Z");

    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private ApplicationEventPublisher events;

    private IncidentEngine engine;
    private Monitor monitor;

    @BeforeEach
    void setUp() {
        // The real state machine: it is pure and fully tested on its own, so no reason to fake it.
        engine = new IncidentEngine(monitorRepository, incidentRepository,
                new MonitorStateMachine(new IncidentProperties(3, 2)), events);
        monitor = new Monitor();
        monitor.setId(MONITOR_ID);
    }

    private void monitorIs(MonitorState state, int failures, int successes) {
        monitor.setState(state);
        monitor.setConsecutiveFailures(failures);
        monitor.setConsecutiveSuccesses(successes);
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
    }

    private static Check check(CheckResult result) {
        Check check = new Check();
        check.setResult(result);
        check.setCheckedAt(CHECKED_AT);
        if (result == CheckResult.DOWN) {
            check.setErrorType(ErrorType.TIMEOUT);
            check.setErrorMessage("No response");
        }
        return check;
    }

    private Incident openIncident() {
        Incident incident = new Incident();
        incident.setId(7L);
        incident.setMonitor(monitor);
        return incident;
    }

    @Test
    void appliesTransitionToMonitorWithoutTouchingIncidents() {
        monitorIs(MonitorState.UP, 0, 0);

        engine.evaluate(MONITOR_ID, check(CheckResult.DOWN));

        assertThat(monitor.getState()).isEqualTo(MonitorState.SUSPICIOUS);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(1);
        verifyNoInteractions(incidentRepository, events);
    }

    @Test
    void opensIncidentAndPublishesEventWhenThresholdReached() {
        monitorIs(MonitorState.SUSPICIOUS, 2, 0);
        when(incidentRepository.findByMonitorIdAndStatus(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        engine.evaluate(MONITOR_ID, check(CheckResult.DOWN));

        assertThat(monitor.getState()).isEqualTo(MonitorState.DOWN);
        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(saved.capture());
        assertThat(saved.getValue().getMonitor()).isSameAs(monitor);
        assertThat(saved.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(saved.getValue().getCause()).isEqualTo("TIMEOUT: No response");
        assertThat(saved.getValue().getStartedAt()).isEqualTo(CHECKED_AT);
        verify(events).publishEvent(any(IncidentOpenedEvent.class));
    }

    @Test
    void doesNotOpenSecondIncidentWhenOneIsAlreadyOpen() {
        monitorIs(MonitorState.SUSPICIOUS, 2, 0);
        when(incidentRepository.findByMonitorIdAndStatus(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.of(openIncident()));

        engine.evaluate(MONITOR_ID, check(CheckResult.DOWN));

        // The state still moves to DOWN; only the duplicate incident is skipped.
        assertThat(monitor.getState()).isEqualTo(MonitorState.DOWN);
        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void resolvesTheExistingIncidentInsteadOfCreatingOne() {
        monitorIs(MonitorState.RECOVERING, 0, 1);
        Incident incident = openIncident();
        when(incidentRepository.findByMonitorIdAndStatus(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.of(incident));

        engine.evaluate(MONITOR_ID, check(CheckResult.UP));

        assertThat(monitor.getState()).isEqualTo(MonitorState.UP);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isEqualTo(CHECKED_AT);
        verify(incidentRepository, never()).save(any());
        verify(events).publishEvent(new IncidentResolvedEvent(7L, MONITOR_ID));
    }

    @Test
    void resolveWithoutOpenIncidentOnlyWarns() {
        monitorIs(MonitorState.RECOVERING, 0, 1);
        when(incidentRepository.findByMonitorIdAndStatus(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        assertThatCode(() -> engine.evaluate(MONITOR_ID, check(CheckResult.UP)))
                .doesNotThrowAnyException();

        assertThat(monitor.getState()).isEqualTo(MonitorState.UP);
        verifyNoInteractions(events);
    }

    @Test
    void ignoresMonitorDeletedDuringCheck() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        engine.evaluate(MONITOR_ID, check(CheckResult.DOWN));

        verifyNoInteractions(incidentRepository, events);
    }
}
