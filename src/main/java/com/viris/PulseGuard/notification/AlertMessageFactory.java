package com.viris.PulseGuard.notification;

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

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public AlertMessage opened(Monitor monitor, Incident incident) {
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
                new Field("Since", since));
        return new AlertMessage("🔴 DOWN: " + monitor.getName(), body, fields,
                "You'll get another message when it recovers.");
    }

    public AlertMessage resolved(Monitor monitor, Incident incident) {
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
                new Field("From", from),
                new Field("To", to));
        return new AlertMessage("✅ RECOVERED: " + monitor.getName(), body, fields, null);
    }

    /** What "Send test" delivers: says plainly that nothing is wrong. */
    public AlertMessage test() {
        String body = """
                This is a test alert from PulseGuard. Nothing is down.

                If you can read this, alerts for your monitors will reach you here.
                """;
        return new AlertMessage("🔔 Test alert from PulseGuard", body);
    }

    private static String format(Instant instant) {
        return TIME.format(instant);
    }

    /** "45s", "14m 30s", "2h 5m": the largest two units, which is all a reader needs. */
    static String humanize(Duration duration) {
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
