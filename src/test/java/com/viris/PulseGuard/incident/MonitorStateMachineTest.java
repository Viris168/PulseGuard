package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.IncidentAction;
import com.viris.PulseGuard.enumeration.MonitorState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.viris.PulseGuard.enumeration.CheckResult.DOWN;
import static com.viris.PulseGuard.enumeration.CheckResult.UP;
import static org.assertj.core.api.Assertions.assertThat;

/** One test per row of the transition table (failure threshold 3, recovery threshold 2). */
class MonitorStateMachineTest {

    private final MonitorStateMachine machine = new MonitorStateMachine(new IncidentProperties(3, 2));

    private static Transition t(MonitorState state, int failures, int successes, IncidentAction action) {
        return new Transition(state, failures, successes, action);
    }

    // --- UP ---------------------------------------------------------------

    @Test
    void staysUpOnPass() {                                                        // row 1
        assertThat(machine.next(MonitorState.UP, 0, 0, UP))
                .isEqualTo(t(MonitorState.UP, 0, 0, IncidentAction.NONE));
    }

    @Test
    void neverOpensIncidentFromASingleFailure() {                                 // row 2
        assertThat(machine.next(MonitorState.UP, 0, 0, DOWN))
                .isEqualTo(t(MonitorState.SUSPICIOUS, 1, 0, IncidentAction.NONE));
    }

    // --- SUSPICIOUS -------------------------------------------------------

    @Test
    void returnsToUpAndForgetsFailuresWhenSuspiciousMonitorPasses() {             // row 3
        assertThat(machine.next(MonitorState.SUSPICIOUS, 2, 0, UP))
                .isEqualTo(t(MonitorState.UP, 0, 0, IncidentAction.NONE));
    }

    @Test
    void keepsCountingBelowTheFailureThreshold() {                                // row 4
        assertThat(machine.next(MonitorState.SUSPICIOUS, 1, 0, DOWN))
                .isEqualTo(t(MonitorState.SUSPICIOUS, 2, 0, IncidentAction.NONE));
    }

    @Test
    void opensIncidentAfterThreeConsecutiveFailures() {                           // row 5
        assertThat(machine.next(MonitorState.SUSPICIOUS, 2, 0, DOWN))
                .isEqualTo(t(MonitorState.DOWN, 3, 0, IncidentAction.OPEN));
    }

    // --- DOWN -------------------------------------------------------------

    @Test
    void staysDownWithoutOpeningAnotherIncident() {                               // row 6
        assertThat(machine.next(MonitorState.DOWN, 3, 0, DOWN))
                .isEqualTo(t(MonitorState.DOWN, 4, 0, IncidentAction.NONE));
    }

    @Test
    void startsRecoveringOnFirstPass() {                                          // row 7
        assertThat(machine.next(MonitorState.DOWN, 5, 0, UP))
                .isEqualTo(t(MonitorState.RECOVERING, 0, 1, IncidentAction.NONE));
    }

    // --- RECOVERING -------------------------------------------------------

    @Test
    void keepsRecoveringBelowTheRecoveryThreshold() {                             // row 8
        MonitorStateMachine slowRecovery = new MonitorStateMachine(new IncidentProperties(3, 3));

        assertThat(slowRecovery.next(MonitorState.RECOVERING, 0, 1, UP))
                .isEqualTo(t(MonitorState.RECOVERING, 0, 2, IncidentAction.NONE));
    }

    @Test
    void resolvesIncidentAfterTwoConsecutivePasses() {                            // row 9
        assertThat(machine.next(MonitorState.RECOVERING, 0, 1, UP))
                .isEqualTo(t(MonitorState.UP, 0, 0, IncidentAction.RESOLVE));
    }

    @Test
    void fallsBackToDownOnFailureWhileRecoveringWithoutANewIncident() {           // row 10
        assertThat(machine.next(MonitorState.RECOVERING, 0, 1, DOWN))
                .isEqualTo(t(MonitorState.DOWN, 1, 0, IncidentAction.NONE));
    }

    // --- edge cases -------------------------------------------------------

    @Test
    void recoversStraightToUpWhenRecoveryThresholdIsOne() {
        MonitorStateMachine quickRecovery = new MonitorStateMachine(new IncidentProperties(3, 1));

        assertThat(quickRecovery.next(MonitorState.DOWN, 3, 0, UP))
                .isEqualTo(t(MonitorState.UP, 0, 0, IncidentAction.RESOLVE));
    }

    @Test
    void opensAtTheConfiguredThresholdNotAHardCodedThree() {
        MonitorStateMachine strict = new MonitorStateMachine(new IncidentProperties(2, 2));

        assertThat(strict.next(MonitorState.SUSPICIOUS, 1, 0, DOWN).action()).isEqualTo(IncidentAction.OPEN);
    }

    @Test
    void aFullOutageOpensAndResolvesExactlyOnce() {
        // blip, real outage, flicker while recovering, then back for good
        List<CheckResult> results = List.of(UP, DOWN, UP, DOWN, DOWN, DOWN, DOWN, UP, DOWN, UP, UP, UP);

        Transition current = t(MonitorState.UP, 0, 0, IncidentAction.NONE);
        List<IncidentAction> actions = new ArrayList<>();
        for (CheckResult result : results) {
            current = machine.next(current.state(), current.consecutiveFailures(),
                    current.consecutiveSuccesses(), result);
            actions.add(current.action());
        }

        assertThat(actions).filteredOn(a -> a == IncidentAction.OPEN).hasSize(1);
        assertThat(actions).filteredOn(a -> a == IncidentAction.RESOLVE).hasSize(1);
        assertThat(actions.indexOf(IncidentAction.OPEN)).isLessThan(actions.indexOf(IncidentAction.RESOLVE));
        assertThat(current.state()).isEqualTo(MonitorState.UP);
    }
}
