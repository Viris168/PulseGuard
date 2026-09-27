package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.incident.dto.IncidentProperties;
import com.viris.PulseGuard.incident.dto.TimelineEvent;
import com.viris.PulseGuard.notification.Notification;
import com.viris.PulseGuard.notification.TargetMasker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns an incident's checks and notifications into its story. Pure: no I/O, the caller
 * loads the rows.
 *
 * <p>Compressed, because a three-day outage at a 60s interval is thousands of identical
 * failures. Kept: every check until the incident opened, every change of result (so a
 * flicker while recovering shows), and the passes that resolved it. Dropped: repeats.
 */
@Component
public class IncidentTimelineBuilder {

    private final IncidentProperties properties;

    public IncidentTimelineBuilder(IncidentProperties properties) {
        this.properties = properties;
    }

    /** @param checks the incident's checks, oldest first */
    public List<TimelineEvent> build(Incident incident, List<Check> checks, List<Notification> notifications) {
        List<TimelineEvent> events = new ArrayList<>();
        boolean resolved = incident.getResolvedAt() != null;
        int finalPasses = resolved ? trailingPasses(checks) : 0;

        boolean opened = false;
        int failuresInARow = 0;
        CheckResult previous = null;
        for (int i = 0; i < checks.size(); i++) {
            Check check = checks.get(i);
            boolean failed = check.getResult() == CheckResult.DOWN;
            failuresInARow = failed ? failuresInARow + 1 : 0;

            boolean changed = previous != check.getResult();
            boolean resolvingPass = i >= checks.size() - finalPasses;
            if (!opened || changed || resolvingPass) {
                events.add(failed
                        ? new TimelineEvent.CheckFailed(check.getCheckedAt(), IncidentEngine.describe(check))
                        : new TimelineEvent.CheckPassed(check.getCheckedAt()));
            }
            if (!opened && failuresInARow == properties.failureThreshold()) {
                events.add(new TimelineEvent.Opened(check.getCheckedAt(), failuresInARow));
                opened = true;
            }
            previous = check.getResult();
        }
        if (!opened) {
            // Incidents recorded before startedAt meant "first failure" begin at the confirming
            // check, so their streak lies outside the window; anchor OPENED at the start instead.
            events.add(new TimelineEvent.Opened(incident.getStartedAt(), properties.failureThreshold()));
        }
        if (resolved) {
            events.add(new TimelineEvent.Resolved(incident.getResolvedAt(),
                    finalPasses > 0 ? finalPasses : properties.recoveryThreshold()));
        }

        for (Notification notification : notifications) {
            // PENDING is a send still in flight (or interrupted); the story shows outcomes only.
            if (notification.getStatus() == NotificationStatus.PENDING) {
                continue;
            }
            events.add(new TimelineEvent.Notified(notification.getSentAt(), notification.getEventType(),
                    notification.getChannel().getType(),
                    TargetMasker.mask(notification.getChannel().getType(), notification.getChannel().getTarget()),
                    notification.getStatus()));
        }

        // Stable sort by time; at the same instant a check comes before what it caused, and a
        // state change before the alert about it.
        events.sort(Comparator.comparing(TimelineEvent::at).thenComparingInt(IncidentTimelineBuilder::rank));
        return events;
    }

    private static int trailingPasses(List<Check> checks) {
        int count = 0;
        for (int i = checks.size() - 1; i >= 0 && checks.get(i).getResult() == CheckResult.UP; i--) {
            count++;
        }
        return count;
    }

    private static int rank(TimelineEvent event) {
        return switch (event) {
            case TimelineEvent.CheckFailed e -> 0;
            case TimelineEvent.CheckPassed e -> 0;
            case TimelineEvent.Opened e -> 1;
            case TimelineEvent.Resolved e -> 1;
            case TimelineEvent.Notified e -> 2;
        };
    }
}
