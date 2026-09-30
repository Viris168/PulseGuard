package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AiAccess;
import com.viris.PulseGuard.ai.PromptText;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.AlertMessageFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The line that tells the model which page a chat was started from, e.g. "The user started this
 * chat from the page of the incident on Health that started Tue 29 Sep, 14:36". Worked out again
 * on every turn from the user's own records: if the monitor is no longer theirs or no longer
 * shared with Ask AI, the line is simply left out.
 */
@Component
public class ChatContextLoader {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private final MonitorRepository monitors;
    private final IncidentRepository incidents;

    public ChatContextLoader(MonitorRepository monitors, IncidentRepository incidents) {
        this.monitors = monitors;
        this.incidents = incidents;
    }

    @Transactional(readOnly = true)
    public Optional<String> describe(AiConversation chat, Long userId, AiAccess access, ZoneId zone, Instant now) {
        if (chat.getContextIncidentId() != null) {
            return incidents.findByIdAndMonitorUserId(chat.getContextIncidentId(), userId)
                    .filter(i -> access.allows(i.getMonitor().getId()))
                    .map(i -> incidentLine(i, zone, now));
        }
        if (chat.getContextMonitorId() != null) {
            return monitors.findByIdAndUserId(chat.getContextMonitorId(), userId)
                    .filter(m -> access.allows(m.getId()))
                    .map(ChatContextLoader::monitorLine);
        }
        return Optional.empty();
    }

    private static String monitorLine(Monitor m) {
        String name = PromptText.clip(m.getName(), 100);
        return "The user started this chat from the page of the monitor " + name
                + ". When they say \"this monitor\" or \"it\", they mean " + name + ".";
    }

    private static String incidentLine(Incident i, ZoneId zone, Instant now) {
        String name = PromptText.clip(i.getMonitor().getName(), 100);
        String started = WHEN.format(i.getStartedAt().atZone(zone));
        String state = i.getResolvedAt() == null
                ? "still ongoing (" + humanize(i.getStartedAt(), now) + " so far)"
                : "resolved " + WHEN.format(i.getResolvedAt().atZone(zone)) + " ("
                + humanize(i.getStartedAt(), i.getResolvedAt()) + ")";
        return "The user started this chat from the page of the incident on " + name + " that started " + started
                + ", " + state + ". When they say \"this incident\" or \"it\", they mean this one; "
                + "get_incident_details with that start time gives its full story.";
    }

    private static String humanize(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return AlertMessageFactory.humanize(d.isNegative() ? Duration.ZERO : d).replace(" 0s", "").replace(" 0m", "");
    }
}
