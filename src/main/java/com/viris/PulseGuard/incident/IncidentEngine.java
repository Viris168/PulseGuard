package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.dto.IncidentOpenedEvent;
import com.viris.PulseGuard.incident.dto.IncidentResolvedEvent;
import com.viris.PulseGuard.incident.dto.Transition;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one check result to its monitor, in one transaction: the new state and counters,
 * the incident opened or resolved, and the event announcing it. The decision itself comes
 * from {@link MonitorStateMachine}; this class only carries it out.
 *
 * <p>Events are published inside the transaction, but notification listeners run after
 * commit, so a rolled-back incident never sends an alert.
 */
@Service
public class IncidentEngine {

    private static final Logger log = LoggerFactory.getLogger(IncidentEngine.class);

    private final MonitorRepository monitorRepository;
    private final IncidentRepository incidentRepository;
    private final MonitorStateMachine stateMachine;
    private final ApplicationEventPublisher events;

    public IncidentEngine(MonitorRepository monitorRepository,
                          IncidentRepository incidentRepository,
                          MonitorStateMachine stateMachine,
                          ApplicationEventPublisher events) {
        this.monitorRepository = monitorRepository;
        this.incidentRepository = incidentRepository;
        this.stateMachine = stateMachine;
        this.events = events;
    }

    @Transactional
    public void evaluate(Long monitorId, Check check) {
        // Loaded fresh: the caller's copy predates the HTTP check and may be stale.
        Monitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null) {
            log.debug("Monitor deleted during its check; nothing to evaluate for monitorId={}", monitorId);
            return;
        }

        Transition transition = stateMachine.next(monitor.getState(),
                monitor.getConsecutiveFailures(), monitor.getConsecutiveSuccesses(), check.getResult());
        // Managed entity: dirty checking writes these at commit, guarded by @Version.
        monitor.setState(transition.state());
        monitor.setConsecutiveFailures(transition.consecutiveFailures());
        monitor.setConsecutiveSuccesses(transition.consecutiveSuccesses());

        switch (transition.action()) {
            case NONE -> { }   // most checks: state and counters changed, nothing to do about incidents
            case OPEN -> open(monitor, check);
            case RESOLVE -> resolve(monitorId, check);
        }
    }

    private void open(Monitor monitor, Check check) {
        Long monitorId = monitor.getId();
        // Idempotent: the partial unique index would reject a second open incident and roll
        // back the whole transaction, state change included.
        if (incidentRepository.findByMonitorIdAndStatus(monitorId, IncidentStatus.OPEN).isPresent()) {
            log.warn("Incident already open for monitorId={}; not opening another", monitorId);
            return;
        }

        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setCause(describe(check));
        incident.setStartedAt(check.getCheckedAt());
        incidentRepository.save(incident);

        events.publishEvent(new IncidentOpenedEvent(incident.getId(), monitorId));
        log.info("Opened incidentId={} for monitorId={}", incident.getId(), monitorId);
    }

    private void resolve(Long monitorId, Check check) {
        // Close the existing incident; a new row would be a second OPEN one.
        incidentRepository.findByMonitorIdAndStatus(monitorId, IncidentStatus.OPEN)
                .ifPresentOrElse(incident -> {
                    incident.setStatus(IncidentStatus.RESOLVED);
                    incident.setResolvedAt(check.getCheckedAt());
                    events.publishEvent(new IncidentResolvedEvent(incident.getId(), monitorId));
                    log.info("Resolved incidentId={} for monitorId={}", incident.getId(), monitorId);
                }, () -> log.warn("No open incident to resolve for monitorId={}", monitorId));
    }

    /** e.g. "TIMEOUT: No response". */
    private String describe(Check check) {
        if (check.getErrorType() == null) {
            return "Check failed";
        }
        return check.getErrorMessage() == null
                ? check.getErrorType().name()
                : check.getErrorType() + ": " + check.getErrorMessage();
    }
}
