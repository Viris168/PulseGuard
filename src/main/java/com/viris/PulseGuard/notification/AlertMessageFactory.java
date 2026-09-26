package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * What an alert says. Pure formatting, no I/O. Written to be understood from a phone lock
 * screen: the subject alone carries status and monitor name.
 */
@Component
public class AlertMessageFactory {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public AlertMessage opened(Monitor monitor, Incident incident) {
        String body = """
                %s is down.

                URL:      %s
                Cause:    %s
                Since:    %s

                You'll get another email when it recovers.
                """.formatted(monitor.getName(), safeUrl(monitor.getUrl()),
                incident.getCause(), format(incident.getStartedAt()));
        return new AlertMessage("🔴 DOWN: " + monitor.getName(), body);
    }

    public AlertMessage resolved(Monitor monitor, Incident incident) {
        String body = """
                %s is back up.

                URL:       %s
                Down for:  %s
                From:      %s
                To:        %s
                """.formatted(monitor.getName(), safeUrl(monitor.getUrl()),
                humanize(Duration.between(incident.getStartedAt(), incident.getResolvedAt())),
                format(incident.getStartedAt()), format(incident.getResolvedAt()));
        return new AlertMessage("✅ RECOVERED: " + monitor.getName(), body);
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
