package com.viris.PulseGuard.incident.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;

import java.time.Instant;

/**
 * One entry of an incident's story; mirrors the {@code IncidentEvent} union in
 * frontend/src/types/incident.ts. Jackson writes the subtype name into a {@code "type"} field,
 * which is exactly how the TypeScript union tells its members apart.
 *
 * <p>Sealed: these five are the only kinds, so a {@code switch} over them needs no default.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TimelineEvent.CheckFailed.class, name = "CHECK_FAILED"),
        @JsonSubTypes.Type(value = TimelineEvent.Opened.class, name = "OPENED"),
        @JsonSubTypes.Type(value = TimelineEvent.Notified.class, name = "NOTIFIED"),
        @JsonSubTypes.Type(value = TimelineEvent.CheckPassed.class, name = "CHECK_PASSED"),
        @JsonSubTypes.Type(value = TimelineEvent.Resolved.class, name = "RESOLVED")
})
public sealed interface TimelineEvent {

    Instant at();

    record CheckFailed(Instant at, String detail) implements TimelineEvent {
    }

    record Opened(Instant at, int failedChecks) implements TimelineEvent {
    }

    /** {@code target} is already masked; see TargetMasker. */
    record Notified(Instant at, NotificationEventType event, ChannelType channel,
                    String target, NotificationStatus status) implements TimelineEvent {
    }

    record CheckPassed(Instant at) implements TimelineEvent {
    }

    record Resolved(Instant at, int passedChecks) implements TimelineEvent {
    }
}
