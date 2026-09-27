package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.incident.dto.IncidentProperties;
import com.viris.PulseGuard.incident.dto.TimelineEvent;
import com.viris.PulseGuard.incident.dto.TimelineEvent.CheckFailed;
import com.viris.PulseGuard.incident.dto.TimelineEvent.CheckPassed;
import com.viris.PulseGuard.incident.dto.TimelineEvent.Notified;
import com.viris.PulseGuard.incident.dto.TimelineEvent.Opened;
import com.viris.PulseGuard.incident.dto.TimelineEvent.Resolved;
import com.viris.PulseGuard.notification.Notification;
import com.viris.PulseGuard.notification.NotificationChannel;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.viris.PulseGuard.enumeration.CheckResult.DOWN;
import static com.viris.PulseGuard.enumeration.CheckResult.UP;
import static org.assertj.core.api.Assertions.assertThat;

class IncidentTimelineBuilderTest {

    private static final Instant T0 = Instant.parse("2026-09-27T11:00:00Z");

    private final IncidentTimelineBuilder builder = new IncidentTimelineBuilder(new IncidentProperties(3, 2));

    /** Minute n after T0. */
    private static Instant min(int n) {
        return T0.plusSeconds(60L * n);
    }

    /** One check per minute from T0, with the given results. */
    private static List<Check> checks(CheckResult... results) {
        List<Check> list = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            Check check = new Check();
            check.setResult(results[i]);
            check.setCheckedAt(min(i));
            if (results[i] == DOWN) {
                check.setErrorType(ErrorType.STATUS_MISMATCH);
                check.setErrorMessage("Expected 200 but got 500");
            }
            list.add(check);
        }
        return list;
    }

    private static Incident incident(Instant resolvedAt) {
        Incident incident = new Incident();
        incident.setStartedAt(T0);
        incident.setResolvedAt(resolvedAt);
        return incident;
    }

    private static Notification notification(Instant at, NotificationEventType event, ChannelType type,
                                             String target, NotificationStatus status) {
        NotificationChannel channel = new NotificationChannel();
        channel.setType(type);
        channel.setTarget(target);
        Notification notification = new Notification();
        notification.setChannel(channel);
        notification.setEventType(event);
        notification.setStatus(status);
        notification.setSentAt(at);
        return notification;
    }

    @Test
    void tellsTheWholeStoryOfAnOutage() {
        List<TimelineEvent> timeline = builder.build(incident(min(6)),
                checks(DOWN, DOWN, DOWN, DOWN, DOWN, UP, UP),
                List.of(notification(min(2).plusSeconds(1), NotificationEventType.OPENED, ChannelType.EMAIL,
                                "owner@example.com", NotificationStatus.SENT),
                        notification(min(6).plusSeconds(1), NotificationEventType.RESOLVED, ChannelType.EMAIL,
                                "owner@example.com", NotificationStatus.SENT)));

        assertThat(timeline).containsExactly(
                new CheckFailed(min(0), "STATUS_MISMATCH: Expected 200 but got 500"),
                new CheckFailed(min(1), "STATUS_MISMATCH: Expected 200 but got 500"),
                new CheckFailed(min(2), "STATUS_MISMATCH: Expected 200 but got 500"),
                new Opened(min(2), 3),
                new Notified(min(2).plusSeconds(1), NotificationEventType.OPENED, ChannelType.EMAIL,
                        "owner@example.com", NotificationStatus.SENT),
                // minutes 3 and 4: the same failure again; compressed away
                new CheckPassed(min(5)),
                new CheckPassed(min(6)),
                new Resolved(min(6), 2),
                new Notified(min(6).plusSeconds(1), NotificationEventType.RESOLVED, ChannelType.EMAIL,
                        "owner@example.com", NotificationStatus.SENT));
    }

    @Test
    void aFlickerWhileRecoveringStaysVisible() {
        List<TimelineEvent> timeline = builder.build(incident(min(6)),
                checks(DOWN, DOWN, DOWN, UP, DOWN, UP, UP), List.of());

        assertThat(timeline).extracting(TimelineEvent::at, e -> e.getClass().getSimpleName()).containsExactly(
                Tuple.tuple(min(0), "CheckFailed"),
                Tuple.tuple(min(1), "CheckFailed"),
                Tuple.tuple(min(2), "CheckFailed"),
                Tuple.tuple(min(2), "Opened"),
                Tuple.tuple(min(3), "CheckPassed"),
                Tuple.tuple(min(4), "CheckFailed"),   // the flicker
                Tuple.tuple(min(5), "CheckPassed"),
                Tuple.tuple(min(6), "CheckPassed"),
                Tuple.tuple(min(6), "Resolved"));
    }

    @Test
    void compressesALongOutageToItsTurningPoints() {
        CheckResult[] results = new CheckResult[300];
        Arrays.fill(results, DOWN);

        List<TimelineEvent> timeline = builder.build(incident(null), checks(results), List.of());

        // 3 failures that opened it + OPENED; the other 297 identical failures add nothing.
        assertThat(timeline).hasSize(4);
    }

    @Test
    void anOpenIncidentHasNoResolvedEvent() {
        List<TimelineEvent> timeline = builder.build(incident(null), checks(DOWN, DOWN, DOWN, DOWN), List.of());

        assertThat(timeline).noneMatch(e -> e instanceof Resolved);
        assertThat(timeline).last().isInstanceOf(Opened.class);
    }

    @Test
    void atTheSameInstantTheCheckComesBeforeTheStateChangeAndTheAlertLast() {
        List<TimelineEvent> timeline = builder.build(incident(null), checks(DOWN, DOWN, DOWN),
                List.of(notification(min(2), NotificationEventType.OPENED, ChannelType.EMAIL,
                        "owner@example.com", NotificationStatus.SENT)));

        assertThat(timeline.subList(2, 5)).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("CheckFailed", "Opened", "Notified");
    }

    @Test
    void hidesPendingSendsAndMasksSecretTargets() {
        List<TimelineEvent> timeline = builder.build(incident(null), checks(DOWN, DOWN, DOWN),
                List.of(notification(min(2), NotificationEventType.OPENED, ChannelType.SLACK,
                                "https://hooks.slack.com/services/T000/B000/SECRET", NotificationStatus.FAILED),
                        notification(min(2), NotificationEventType.OPENED, ChannelType.EMAIL,
                                "owner@example.com", NotificationStatus.PENDING)));

        List<Notified> alerts = timeline.stream().filter(Notified.class::isInstance).map(Notified.class::cast).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().target()).isEqualTo("https://hooks.slack.com/••••").doesNotContain("SECRET");
        assertThat(alerts.getFirst().status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void anIncidentRecordedBeforeTheStartedAtFixStillGetsAnOpenedEvent() {
        // Old rows start at the confirming (third) check, so the streak is outside the window.
        List<TimelineEvent> timeline = builder.build(incident(min(2)), checks(DOWN, UP, UP), List.of());

        assertThat(timeline).contains(new Opened(T0, 3), new Resolved(min(2), 2));
    }
}
