package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.enumeration.IncidentAction;
import com.viris.PulseGuard.enumeration.MonitorState;

public record Transition(
        MonitorState state,
        int consecutiveFailures,
        int consecutiveSuccesses,
        IncidentAction action
) {}