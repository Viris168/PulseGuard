package com.viris.PulseGuard.common.exception;

/** Also thrown for another tenant's incident, so ids cannot be probed. */
public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(Long incidentId) {
        super("Incident not found: " + incidentId);
    }
}
