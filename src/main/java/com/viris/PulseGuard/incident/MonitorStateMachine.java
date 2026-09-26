package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.IncidentAction;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.incident.dto.IncidentProperties;
import com.viris.PulseGuard.incident.dto.Transition;
import org.springframework.stereotype.Component;

/**
 * The monitor state machine as pure calculation: no database, no side effects. Given the
 * current state, counters and a check result, it returns what the monitor should become and
 * what to do about its incident. {@code IncidentEngine} applies the answer.
 *
 * <p>The counters follow one rule in every state: a pass zeroes failures and counts up
 * successes, a failure does the reverse, so thresholds always mean "in a row".
 */
@Component
public class MonitorStateMachine {

    private final IncidentProperties properties;

    public MonitorStateMachine(IncidentProperties properties) {
        this.properties = properties;
    }

    public Transition next(MonitorState state, int failures, int successes, CheckResult result) {
        boolean passed = result == CheckResult.UP;
        int newFailures = passed ? 0 : failures + 1;
        int newSuccesses = passed ? successes + 1 : 0;

        return switch (state) {
            case UP -> passed
                    ? new Transition(MonitorState.UP, 0, 0, IncidentAction.NONE)
                    // Never an incident from one failure: only suspicion.
                    : new Transition(MonitorState.SUSPICIOUS, newFailures, 0, IncidentAction.NONE);

            case SUSPICIOUS -> {
                if (passed) {
                    // A blip: forget it entirely, so unrelated failures never add up.
                    yield new Transition(MonitorState.UP, 0, 0, IncidentAction.NONE);
                }
                if (newFailures >= properties.failureThreshold()) {
                    yield new Transition(MonitorState.DOWN, newFailures, 0, IncidentAction.OPEN);
                }
                yield new Transition(MonitorState.SUSPICIOUS, newFailures, 0, IncidentAction.NONE);
            }

            case DOWN -> {
                if (!passed) {
                    yield new Transition(MonitorState.DOWN, newFailures, 0, IncidentAction.NONE);
                }
                // With a recovery threshold of 1, the first pass is already enough.
                if (newSuccesses >= properties.recoveryThreshold()) {
                    yield new Transition(MonitorState.UP, 0, 0, IncidentAction.RESOLVE);
                }
                yield new Transition(MonitorState.RECOVERING, 0, newSuccesses, IncidentAction.NONE);
            }

            case RECOVERING -> {
                if (!passed) {
                    // A flicker during recovery: same outage, the incident stays open.
                    yield new Transition(MonitorState.DOWN, newFailures, 0, IncidentAction.NONE);
                }
                if (newSuccesses >= properties.recoveryThreshold()) {
                    yield new Transition(MonitorState.UP, 0, 0, IncidentAction.RESOLVE);
                }
                yield new Transition(MonitorState.RECOVERING, 0, newSuccesses, IncidentAction.NONE);
            }
        };
    }
}
