package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.common.config.AppProperties;
import com.viris.PulseGuard.enumeration.AlertLevel;
import com.viris.PulseGuard.heartbeat.HeartbeatSchedule;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.dto.AlertMessage.Field;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What an alert says. Pure formatting, no I/O. Written to be understood from a phone lock
 * screen: the subject alone carries status and monitor name.
 */
@Component
public class AlertMessageFactory {

    private final AppProperties app;

    public AlertMessageFactory(AppProperties app) {
        this.app = app;
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public AlertMessage opened(Monitor monitor, Incident incident) {
        if (monitor.isHeartbeat()) {
            return heartbeatMissed(monitor, incident);
        }
        String url = safeUrl(monitor.getUrl());
        String since = format(incident.getStartedAt());
        String body = """
                %s is down.

                URL:      %s
                Cause:    %s
                Since:    %s

                You'll get another email when it recovers.
                """.formatted(monitor.getName(), url, incident.getCause(), since);
        List<Field> fields = List.of(
                new Field("URL", url),
                new Field("Cause", incident.getCause()),
                new Field("Since", since, incident.getStartedAt()));
        return new AlertMessage("🔴 DOWN: " + monitor.getName(), body, AlertLevel.CRITICAL, fields,
                "You'll get another message when it recovers.", incidentLink(incident));
    }

    public AlertMessage resolved(Monitor monitor, Incident incident) {
        if (monitor.isHeartbeat()) {
            return heartbeatBack(monitor, incident);
        }
        String url = safeUrl(monitor.getUrl());
        String downFor = humanize(Duration.between(incident.getStartedAt(), incident.getResolvedAt()));
        String from = format(incident.getStartedAt());
        String to = format(incident.getResolvedAt());
        String body = """
                %s is back up.

                URL:       %s
                Down for:  %s
                From:      %s
                To:        %s
                """.formatted(monitor.getName(), url, downFor, from, to);
        List<Field> fields = List.of(
                new Field("URL", url),
                new Field("Down for", downFor),
                new Field("From", from, incident.getStartedAt()),
                new Field("To", to, incident.getResolvedAt()));
        return new AlertMessage("✅ RECOVERED: " + monitor.getName(), body, AlertLevel.RESOLVED, fields, null,
                incidentLink(incident));
    }

    /** A heartbeat has no URL of ours to show; what matters is the schedule it broke. */
    private AlertMessage heartbeatMissed(Monitor monitor, Incident incident) {
        String expected = "a ping every " + HeartbeatSchedule.describe(monitor);
        String lastPing = monitor.getLastCheckedAt() == null ? "never" : format(monitor.getLastCheckedAt());
        String since = format(incident.getStartedAt());
        String body = """
                %s missed its check-in.

                Expected:  %s
                Last ping: %s
                Since:     %s

                You'll get another email when it pings again.
                """.formatted(monitor.getName(), expected, lastPing, since);
        List<Field> fields = List.of(
                new Field("Expected", expected),
                monitor.getLastCheckedAt() == null
                        ? new Field("Last ping", lastPing)
                        : new Field("Last ping", lastPing, monitor.getLastCheckedAt()),
                new Field("Since", since, incident.getStartedAt()));
        return new AlertMessage("🔴 DOWN: " + monitor.getName(), body, AlertLevel.CRITICAL, fields,
                "You'll get another message when it pings again.", incidentLink(incident));
    }

    private AlertMessage heartbeatBack(Monitor monitor, Incident incident) {
        String silentFor = humanize(Duration.between(incident.getStartedAt(), incident.getResolvedAt()));
        String from = format(incident.getStartedAt());
        String to = format(incident.getResolvedAt());
        String body = """
                %s checked in again.

                Silent for: %s
                From:       %s
                To:         %s
                """.formatted(monitor.getName(), silentFor, from, to);
        List<Field> fields = List.of(
                new Field("Silent for", silentFor),
                new Field("From", from, incident.getStartedAt()),
                new Field("To", to, incident.getResolvedAt()));
        return new AlertMessage("✅ RECOVERED: " + monitor.getName(), body, AlertLevel.RESOLVED, fields, null,
                incidentLink(incident));
    }

    /** What "Send test" delivers: says plainly that nothing is wrong. */
    public AlertMessage test() {
        String body = """
                This is a test alert from PulseGuard. Nothing is down.

                If you can read this, alerts for your monitors will reach you here.
                """;
        return new AlertMessage("🔔 Test alert from PulseGuard", body);
    }

    /** The incident's page in the frontend; null before the incident has an id. */
    private String incidentLink(Incident incident) {
        return incident.getId() == null ? null : app.url("/incidents/" + incident.getId());
    }

    private static String format(Instant instant) {
        return TIME.format(instant);
    }

    /** "45s", "14m 30s", "2h 5m": the largest two units, which is all a reader needs. */
    public static String humanize(Duration duration) {
        long hours = duration.toHours();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    /**
     * Email is not a secure channel: it is forwarded, stored and synced. Monitored URLs can
     * carry credentials ({@code user:pass@}) or API keys in the query string, so only scheme,
     * host, port and path are shown. Unparseable input is never echoed back.
     */
    static String safeUrl(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                return "(invalid URL)";
            }
            StringBuilder safe = new StringBuilder()
                    .append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                safe.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                safe.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                safe.append("?…");
            }
            return safe.toString();
        } catch (Exception e) {
            return "(invalid URL)";
        }
    }
}
