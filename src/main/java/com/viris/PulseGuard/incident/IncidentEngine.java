package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.enumeration.IncidentAction;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.dto.IncidentOpenedEvent;
import com.viris.PulseGuard.incident.dto.IncidentResolvedEvent;
import com.viris.PulseGuard.incident.dto.Transition;
import com.viris.PulseGuard.heartbeat.HeartbeatSchedule;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
    private final CheckRepository checkRepository;

    public IncidentEngine(MonitorRepository monitorRepository,
                          IncidentRepository incidentRepository,
                          MonitorStateMachine stateMachine,
                          ApplicationEventPublisher events,
                          CheckRepository checkRepository) {
        this.monitorRepository = monitorRepository;
        this.incidentRepository = incidentRepository;
        this.stateMachine = stateMachine;
        this.events = events;
        this.checkRepository = checkRepository;
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
            case OPEN -> open(monitor, check, transition.consecutiveFailures());
            case RESOLVE -> resolve(monitorId, check);
        }
    }

    /**
     * A heartbeat's ping, in the caller's transaction (which has saved the ping itself). Records
     * it as a passing check, so uptime, status bars and the status page count it like any check.
     */
    @Transactional
    public void heartbeatReceived(Long monitorId, Instant at) {
        Monitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null || !monitor.isHeartbeat()) {
            return;
        }
        Check pass = new Check();
        pass.setMonitor(monitor);
        pass.setResult(CheckResult.UP);
        pass.setCheckedAt(at);
        checkRepository.save(pass);

        Transition transition = stateMachine.heartbeatPinged(monitor.getState());
        apply(monitor, transition);
        monitor.setLastCheckedAt(at);
        monitor.setPingDeadline(HeartbeatSchedule.deadlineAfter(at, monitor));
        if (transition.action() == IncidentAction.RESOLVE) {
            resolve(monitorId, pass);
        }
    }

    /**
     * A heartbeat whose deadline passed by {@code now}. Re-checked here, inside the transaction,
     * because a ping may have landed since the sweeper picked it; @Version settles the race.
     */
    @Transactional
    public void heartbeatMissed(Long monitorId, Instant now) {
        Monitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null || !monitor.isHeartbeat() || !monitor.isActive()
                || monitor.getPingDeadline() == null || monitor.getPingDeadline().isAfter(now)) {
            return;
        }
        Instant missedAt = monitor.getPingDeadline();
        String message = HeartbeatSchedule.missedMessage(monitor);
        Check miss = new Check();
        miss.setMonitor(monitor);
        miss.setResult(CheckResult.DOWN);
        miss.setErrorType(ErrorType.TIMEOUT);
        miss.setErrorMessage(message);
        miss.setCheckedAt(missedAt);
        checkRepository.save(miss);

        Transition transition = stateMachine.heartbeatMissed(monitor.getState(), monitor.getConsecutiveFailures());
        apply(monitor, transition);
        monitor.setPingDeadline(HeartbeatSchedule.nextDeadlineAfterMiss(missedAt, monitor, now));
        if (transition.action() == IncidentAction.OPEN) {
            // The outage starts at the missed deadline, and the cause is said plainly: there is
            // no error type to show for silence.
            openIncident(monitor, message, missedAt);
        } else {
            log.debug("Heartbeat still missing for monitorId={}", monitorId);
        }
    }

    private static void apply(Monitor monitor, Transition transition) {
        monitor.setState(transition.state());
        monitor.setConsecutiveFailures(transition.consecutiveFailures());
        monitor.setConsecutiveSuccesses(transition.consecutiveSuccesses());
    }

    private void open(Monitor monitor, Check check, int failuresInStreak) {
        openIncident(monitor, describe(check), firstFailureAt(monitor.getId(), failuresInStreak, check));
    }

    private void openIncident(Monitor monitor, String cause, Instant startedAt) {
        Long monitorId = monitor.getId();
        // Idempotent: the partial unique index would reject a second open incident and roll
        // back the whole transaction, state change included.
        if (incidentRepository.findByMonitorIdAndStatus(monitorId, IncidentStatus.OPEN).isPresent()) {
            log.warn("Incident already open for monitorId={}; not opening another", monitorId);
            return;
        }

        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setCause(cause);
        incident.setStartedAt(startedAt);
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

    /**
     * The outage began at the first failure of the streak, not at the check that confirmed it:
     * the threshold only proves the outage, it does not move its start. The current check is
     * already saved, so the streak is the latest {@code failuresInStreak} rows; walking back
     * only over DOWN rows keeps a stray UP row from ever being taken as the start.
     */
    private Instant firstFailureAt(Long monitorId, int failuresInStreak, Check current) {
        List<Check> latest = checkRepository.findByMonitorIdOrderByCheckedAtDesc(
                monitorId, PageRequest.of(0, failuresInStreak));
        Instant start = current.getCheckedAt();
        for (Check check : latest) {
            if (check.getResult() != CheckResult.DOWN) {
                break;
            }
            if (check.getCheckedAt().isBefore(start)) {
                start = check.getCheckedAt();
            }
        }
        return start;
    }

    /** e.g. "TIMEOUT: No response". Shared with the incident timeline. */
    static String describe(Check check) {
        if (check.getErrorType() == null) {
            return "Check failed";
        }
        return check.getErrorMessage() == null
                ? check.getErrorType().name()
                : check.getErrorType() + ": " + check.getErrorMessage();
    }
}
