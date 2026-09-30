package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.ai.PromptText;
import com.viris.PulseGuard.ai.tools.ToolQueries.Day;
import com.viris.PulseGuard.ai.tools.ToolQueries.Failure;
import com.viris.PulseGuard.ai.tools.ToolQueries.Failures;
import com.viris.PulseGuard.ai.tools.ToolQueries.Figures;
import com.viris.PulseGuard.ai.tools.ToolQueries.IncidentRef;
import com.viris.PulseGuard.ai.tools.ToolQueries.MonitorRef;
import com.viris.PulseGuard.enumeration.MonitorType;
import com.viris.PulseGuard.incident.IncidentQueryService;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import com.viris.PulseGuard.incident.dto.TimelineEvent;
import com.viris.PulseGuard.notification.AlertMessageFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Ask AI's read-only tools (AI_MILESTONE_2.md, Part 4). The model picks a tool and its arguments;
 * everything about <em>whose</em> data comes from the {@link ToolScope} in the {@link ToolContext},
 * set by the server. A monitor is named as the user names it, and only monitors shared with Ask AI
 * can be found: another user's monitor, or an unshared one, is simply not there.
 *
 * <p>Answers are plain text for the model to read, times in the user's time zone. A bad argument
 * gets a message saying what to fix, rather than an error.
 */
@Component
public class MonitorTools {

    static final int MAX_INCIDENTS = 20;
    static final int MAX_FAILURES = 10;
    static final int PER_DAY_MAX_DAYS = 31;
    static final int MAX_TIMELINE_LINES = 40;
    /** An incident "near" the time the model gave: within this, the nearest one is taken. */
    static final Duration NEAR = Duration.ofHours(12);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private final ToolQueries queries;
    private final IncidentQueryService incidentQueries;

    public MonitorTools(ToolQueries queries, IncidentQueryService incidentQueries) {
        this.queries = queries;
        this.incidentQueries = incidentQueries;
    }

    @Tool(name = "get_uptime", resultConverter = PlainTextResult.class, description = """
            Uptime of one monitor between two dates (both included): the percentage of checks that \
            passed, check counts, and a per-day breakdown for ranges up to 31 days. Use it for any \
            period that <data> doesn't cover.""")
    public String getUptime(
            @ToolParam(description = "Monitor name, as listed in <data>") String monitor,
            @ToolParam(description = "First day, yyyy-MM-dd, in the user's time zone") String from,
            @ToolParam(description = "Last day, yyyy-MM-dd, in the user's time zone") String to,
            ToolContext context) {
        return answer(() -> {
            ToolScope scope = ToolScope.from(context);
            MonitorRef m = find(monitor, scope);
            Period p = period(from, to, scope);
            Figures f = queries.figures(m.id(), p.from(), p.start(), p.end(), scope.now());
            List<String> lines = new ArrayList<>();
            lines.add("Uptime of " + name(m) + ", " + p.label() + " (" + scope.zone().getId() + "):");
            if (f.total() == 0) {
                lines.add("No checks were recorded in that period (the monitor may have been paused, or added later).");
            } else {
                lines.add(pct(f.up(), f.total()) + " up: " + count(f.total()) + " checks, "
                        + count(f.total() - f.up()) + " failed.");
                if (f.days().size() <= PER_DAY_MAX_DAYS && f.days().size() > 1) {
                    lines.add("Per day:");
                    for (Day d : f.days()) {
                        lines.add("- " + DAY.format(d.day()) + ": " + pct(d.up(), d.total()) + " (" + count(d.total())
                                + " checks, " + count(d.total() - d.up()) + " failed)");
                    }
                }
            }
            lines.addAll(p.notes());
            if (f.fromDailySummaries()) {
                lines.add(SUMMARIES_NOTE);
            }
            return String.join("\n", lines);
        });
    }

    @Tool(name = "get_response_times", resultConverter = PlainTextResult.class, description = """
            Response times of one monitor between two dates (both included): average and 95th \
            percentile over passing checks, and the slowest and fastest days.""")
    public String getResponseTimes(
            @ToolParam(description = "Monitor name, as listed in <data>") String monitor,
            @ToolParam(description = "First day, yyyy-MM-dd, in the user's time zone") String from,
            @ToolParam(description = "Last day, yyyy-MM-dd, in the user's time zone") String to,
            ToolContext context) {
        return answer(() -> {
            ToolScope scope = ToolScope.from(context);
            MonitorRef m = find(monitor, scope);
            Period p = period(from, to, scope);
            Figures f = queries.figures(m.id(), p.from(), p.start(), p.end(), scope.now());
            List<String> lines = new ArrayList<>();
            lines.add("Response times of " + name(m) + ", " + p.label() + " (" + scope.zone().getId() + "):");
            if (f.avgMs() == null) {
                lines.add("No passing checks in that period, so there are no response times.");
            } else {
                lines.add("average " + f.avgMs() + " ms, " + (f.fromDailySummaries() ? "worst daily p95 " : "p95 ")
                        + f.p95Ms() + " ms, over " + count(f.up()) + " passing checks.");
                List<Day> timed = f.days().stream().filter(d -> d.avgMs() != null).toList();
                if (timed.size() > 1) {
                    Day slowest = timed.stream().max(Comparator.comparing(Day::avgMs)).orElseThrow();
                    Day fastest = timed.stream().min(Comparator.comparing(Day::avgMs)).orElseThrow();
                    lines.add("Slowest day: " + DAY.format(slowest.day()) + " (average " + slowest.avgMs() + " ms). "
                            + "Fastest day: " + DAY.format(fastest.day()) + " (average " + fastest.avgMs() + " ms).");
                }
            }
            lines.addAll(p.notes());
            if (f.fromDailySummaries()) {
                lines.add(SUMMARIES_NOTE);
            }
            return String.join("\n", lines);
        });
    }

    @Tool(name = "get_incidents", resultConverter = PlainTextResult.class, description = """
            Incidents (confirmed outages) between two dates (both included), newest first, with \
            start, end, duration and cause. Leave monitor empty for all monitors.""")
    public String getIncidents(
            @ToolParam(description = "First day, yyyy-MM-dd, in the user's time zone") String from,
            @ToolParam(description = "Last day, yyyy-MM-dd, in the user's time zone") String to,
            @ToolParam(description = "Monitor name, as listed in <data>; empty for all monitors", required = false)
            String monitor,
            ToolContext context) {
        return answer(() -> {
            ToolScope scope = ToolScope.from(context);
            Period p = period(from, to, scope);
            List<Long> ids;
            String which;
            if (monitor == null || monitor.isBlank()) {
                ids = queries.sharedMonitors(scope).stream().map(MonitorRef::id).toList();
                which = "all shared monitors";
            } else {
                MonitorRef m = find(monitor, scope);
                ids = List.of(m.id());
                which = name(m);
            }
            List<IncidentRef> found = queries.incidents(ids, p.start(), p.end(), MAX_INCIDENTS);
            List<String> lines = new ArrayList<>();
            if (found.isEmpty()) {
                lines.add("No incidents on " + which + ", " + p.label() + ".");
            } else {
                boolean more = found.size() > MAX_INCIDENTS;
                List<IncidentRef> shown = more ? found.subList(0, MAX_INCIDENTS) : found;
                lines.add((more ? "More than " + MAX_INCIDENTS : String.valueOf(shown.size())) + " incident"
                        + (shown.size() == 1 && !more ? "" : "s") + " on " + which + ", " + p.label()
                        + ", newest first:");
                shown.forEach(i -> lines.add("- " + incidentLine(i, scope)));
                if (more) {
                    lines.add("…and more. Narrow the dates or pick one monitor to see them all.");
                }
            }
            lines.addAll(p.notes());
            return String.join("\n", lines);
        });
    }

    @Tool(name = "get_incident_details", resultConverter = PlainTextResult.class, description = """
            The full story of one incident: the failed checks and their errors, when it was \
            confirmed, which alerts were sent, and how it recovered. Give the monitor and when the \
            incident started, or leave start empty for its latest incident.""")
    public String getIncidentDetails(
            @ToolParam(description = "Monitor name, as listed in <data>") String monitor,
            @ToolParam(description = "When the incident started, yyyy-MM-dd HH:mm in the user's time zone; "
                    + "empty for the latest", required = false) String start,
            ToolContext context) {
        return answer(() -> {
            ToolScope scope = ToolScope.from(context);
            MonitorRef m = find(monitor, scope);
            List<IncidentRef> candidates = queries.incidents(List.of(m.id()), earliest(scope), scope.now(), 50);
            if (candidates.isEmpty()) {
                return "No incidents on " + name(m) + " in the history your plan keeps (" + scope.historyDays() + " days).";
            }
            IncidentRef chosen = candidates.getFirst();
            if (start != null && !start.isBlank() && !"latest".equalsIgnoreCase(start.strip())) {
                Instant wanted = localDateTime(start).atZone(scope.zone()).toInstant();
                chosen = candidates.stream()
                        .min(Comparator.comparing(i -> Duration.between(i.startedAt(), wanted).abs()))
                        .orElseThrow();
                if (Duration.between(chosen.startedAt(), wanted).abs().compareTo(NEAR) > 0) {
                    return "No incident on " + name(m) + " started near " + start.strip() + ". Recent ones started: "
                            + String.join("; ", candidates.stream().limit(5)
                            .map(i -> WHEN.format(i.startedAt().atZone(scope.zone()))).toList()) + ".";
                }
            }
            return details(incidentQueries.detail(scope.userId(), chosen.id()), m, scope);
        });
    }

    @Tool(name = "get_recent_failures", resultConverter = PlainTextResult.class, description = """
            Failed checks of one monitor, newest first: when, the HTTP status and the error, and how \
            many failed in all. Give from and to for a period, such as yesterday; leave them empty \
            for the latest failures. Use it to explain why a monitor failed.""")
    public String getRecentFailures(
            @ToolParam(description = "Monitor name, as listed in <data>") String monitor,
            @ToolParam(description = "How many to list, 1 to 10; default 5", required = false) Integer limit,
            @ToolParam(description = "First day, yyyy-MM-dd, in the user's time zone; empty for the latest failures",
                    required = false) String from,
            @ToolParam(description = "Last day, yyyy-MM-dd, in the user's time zone; empty for up to today",
                    required = false) String to,
            ToolContext context) {
        return answer(() -> {
            ToolScope scope = ToolScope.from(context);
            MonitorRef m = find(monitor, scope);
            int n = limit == null ? 5 : Math.clamp(limit, 1, MAX_FAILURES);
            boolean dated = !isBlank(from) || !isBlank(to);
            if (dated && isBlank(from)) {
                throw new ToolInputException("Give the first day (from) as well, yyyy-MM-dd.");
            }
            Period p = dated ? period(from, isBlank(to) ? scope.today().toString() : to, scope) : null;
            Failures f = dated
                    ? queries.failures(m.id(), p.start(), p.end(), scope.now(), n)
                    : queries.failures(m.id(), earliest(scope), scope.now(), scope.now(), n);
            List<String> lines = new ArrayList<>();
            if (f.latest().isEmpty()) {
                lines.add(dated
                        ? "No failed checks on " + name(m) + ", " + p.label() + "."
                        : "No failed checks on " + name(m) + " in the history your plan keeps (" + scope.historyDays() + " days).");
            } else if (dated) {
                lines.add(count(f.total()) + " failed check" + (f.total() == 1 ? "" : "s") + " on " + name(m) + ", "
                        + p.label() + " (" + scope.zone().getId() + ")"
                        + (f.total() > f.latest().size() ? "; the latest " + f.latest().size() + ", newest first:" : ", newest first:"));
            } else {
                lines.add("Latest " + f.latest().size() + " failed check" + (f.latest().size() == 1 ? "" : "s") + " of "
                        + name(m) + ", newest first (" + scope.zone().getId() + "), of " + count(f.total())
                        + " in the history your plan keeps:");
            }
            for (Failure c : f.latest()) {
                lines.add("- " + failureLine(c, scope));
            }
            if (dated) {
                lines.addAll(p.notes());
            }
            if (f.pastRawChecks()) {
                lines.add("Note: single checks are kept for " + f.rawCheckDays() + " days, so older failures can't be "
                        + "listed; get_uptime still has daily counts for them.");
            }
            return String.join("\n", lines);
        });
    }

    private static String failureLine(Failure f, ToolScope scope) {
        List<String> parts = new ArrayList<>();
        if (f.statusCode() != null) {
            parts.add("HTTP " + f.statusCode());
        }
        if (f.errorType() != null || f.errorMessage() != null) {
            parts.add(PromptText.clip((f.errorType() == null ? "" : f.errorType() + ": ")
                    + (f.errorMessage() == null ? "" : f.errorMessage()), 200));
        }
        if (f.responseTimeMs() != null) {
            parts.add(f.responseTimeMs() + " ms");
        }
        return WHEN.format(f.checkedAt().atZone(scope.zone())) + ": " + (parts.isEmpty() ? "failed" : String.join(", ", parts));
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ─── monitors, dates, formatting ─────────────────────────────────────────

    static final String SUMMARIES_NOTE = "Note: this range is older than the raw check history, so these "
            + "figures come from daily summaries whose days run midnight to midnight UTC; today isn't included.";

    /** A bad argument: the message goes back to the model, telling it what to fix. */
    static final class ToolInputException extends RuntimeException {
        ToolInputException(String message) {
            super(message, null, false, false);
        }
    }

    private static String answer(Supplier<String> tool) {
        try {
            return tool.get();
        } catch (ToolInputException e) {
            return e.getMessage();
        }
    }

    /**
     * A shared monitor by name, ignoring case. Missing, unshared and other users' monitors all get
     * the same answer: they aren't in the list this search looks through.
     */
    private MonitorRef find(String name, ToolScope scope) {
        if (name == null || name.isBlank()) {
            throw new ToolInputException("Give the monitor's name, as listed in <data>.");
        }
        List<MonitorRef> shared = queries.sharedMonitors(scope);
        List<MonitorRef> matches = shared.stream().filter(m -> m.name().equalsIgnoreCase(name.strip())).toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new ToolInputException("Several shared monitors are named " + PromptText.clip(name, 100)
                    + ": " + String.join("; ", matches.stream().map(MonitorTools::describe).toList())
                    + ". Ask the user which one they mean.");
        }
        throw new ToolInputException("No monitor named " + PromptText.clip(name, 100) + " is shared with Ask AI. "
                + (shared.isEmpty() ? "No monitors are shared." : "Shared monitors: "
                + String.join(", ", shared.stream().limit(30).map(MonitorTools::name).toList()) + "."));
    }

    /** From and to as the user meant them, clamped to what the plan keeps and to today. */
    record Period(LocalDate from, LocalDate to, Instant start, Instant end, List<String> notes) {
        String label() {
            return from.equals(to) ? DAY.format(from) : DAY.format(from) + " to " + DAY.format(to);
        }
    }

    private static Period period(String fromText, String toText, ToolScope scope) {
        LocalDate from = date(fromText);
        LocalDate to = date(toText);
        if (from.isAfter(to)) {
            throw new ToolInputException("The first day (" + from + ") is after the last day (" + to + ").");
        }
        LocalDate earliest = scope.earliestDay();
        LocalDate today = scope.today();
        List<String> notes = new ArrayList<>();
        if (to.isBefore(earliest)) {
            throw new ToolInputException("That range is older than the history this plan keeps (" + scope.historyDays()
                    + " days, back to " + DAY.format(earliest) + "). Tell the user it can't be looked up.");
        }
        if (from.isBefore(earliest)) {
            notes.add("Note: this plan keeps " + scope.historyDays() + " days of history, so the range starts on "
                    + DAY.format(earliest) + " instead of " + DAY.format(from) + ".");
            from = earliest;
        }
        if (from.isAfter(today)) {
            throw new ToolInputException("That range is in the future; today is " + DAY.format(today) + ".");
        }
        if (to.isAfter(today)) {
            notes.add("Note: the range ends today, " + DAY.format(today) + ", so far.");
            to = today;
        }
        Instant start = from.atStartOfDay(scope.zone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(scope.zone()).toInstant();
        return new Period(from, to, start, end.isAfter(scope.now()) ? scope.now() : end, notes);
    }

    private static Instant earliest(ToolScope scope) {
        return scope.earliestDay().atStartOfDay(scope.zone()).toInstant();
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text.strip());
        } catch (DateTimeParseException e) {
            throw new ToolInputException("Dates must be yyyy-MM-dd, for example 2026-09-01.");
        }
    }

    private static LocalDateTime localDateTime(String text) {
        String t = text.strip().replace('T', ' ');
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(t).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new ToolInputException("Give the start as yyyy-MM-dd HH:mm, for example 2026-09-29 14:36.");
            }
        }
    }

    private static String details(IncidentDetailResponse d, MonitorRef m, ToolScope scope) {
        List<String> lines = new ArrayList<>();
        String started = WHEN.format(d.startedAt().atZone(scope.zone()));
        lines.add("Incident on " + name(m) + ": started " + started + (d.resolvedAt() == null
                ? ", still ongoing (" + humanize(d.startedAt(), scope.now()) + " so far)."
                : ", resolved " + WHEN.format(d.resolvedAt().atZone(scope.zone())) + " ("
                + humanize(d.startedAt(), d.resolvedAt()) + ")."));
        if (d.cause() != null) {
            lines.add("Cause when it opened: " + PromptText.clip(d.cause(), 200));
        }
        lines.add("Timeline (" + scope.zone().getId() + "):");
        List<String> events = new ArrayList<>();
        String lastFailure = null;
        int repeats = 0;
        for (TimelineEvent e : d.timeline()) {
            if (e instanceof TimelineEvent.CheckFailed f && PromptText.clip(f.detail(), 200).equals(lastFailure)) {
                repeats++;
                continue;
            }
            if (repeats > 0) {
                events.set(events.size() - 1, events.getLast() + " (and " + repeats + " more like it)");
                repeats = 0;
            }
            lastFailure = null;
            String at = WHEN.format(e.at().atZone(scope.zone())) + " ";
            switch (e) {
                case TimelineEvent.CheckFailed f -> {
                    lastFailure = PromptText.clip(f.detail(), 200);
                    events.add(at + "failed check: " + lastFailure);
                }
                case TimelineEvent.Opened o -> events.add(at + "incident confirmed after " + o.failedChecks()
                        + " failed checks in a row");
                case TimelineEvent.Notified n -> events.add(at + n.event().name().toLowerCase(Locale.ROOT) + " alert by "
                        + n.channel().name().toLowerCase(Locale.ROOT) + ": " + n.status().name().toLowerCase(Locale.ROOT));
                case TimelineEvent.CheckPassed p -> events.add(at + "check passed");
                case TimelineEvent.Resolved r -> events.add(at + "resolved after " + r.passedChecks()
                        + " passing checks in a row");
            }
        }
        if (repeats > 0) {
            events.set(events.size() - 1, events.getLast() + " (and " + repeats + " more like it)");
        }
        events.stream().limit(MAX_TIMELINE_LINES).forEach(line -> lines.add("- " + line));
        if (events.size() > MAX_TIMELINE_LINES) {
            lines.add("…and " + (events.size() - MAX_TIMELINE_LINES) + " more events.");
        }
        return String.join("\n", lines);
    }

    private static String incidentLine(IncidentRef i, ToolScope scope) {
        String started = WHEN.format(i.startedAt().atZone(scope.zone()));
        String span = i.resolvedAt() == null
                ? "since " + started + " (ongoing, " + humanize(i.startedAt(), scope.now()) + " so far)"
                : started + " to " + WHEN.format(i.resolvedAt().atZone(scope.zone())) + " ("
                + humanize(i.startedAt(), i.resolvedAt()) + ")";
        String cause = i.cause() == null ? "" : "; cause: " + PromptText.clip(i.cause(), 200);
        return PromptText.clip(i.monitorName(), 100) + ": " + span + cause;
    }

    private static String name(MonitorRef m) {
        return PromptText.clip(m.name(), 100);
    }

    private static String describe(MonitorRef m) {
        return name(m) + " (" + (m.type() == MonitorType.HEARTBEAT ? "heartbeat" : "website/API check")
                + " every " + humanize(Duration.ofSeconds(m.intervalSeconds())) + ")";
    }

    private static String humanize(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return humanize(d.isNegative() ? Duration.ZERO : d);
    }

    private static String humanize(Duration d) {
        return AlertMessageFactory.humanize(d).replace(" 0s", "").replace(" 0m", "");
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "no checks" : String.format(Locale.ENGLISH, "%.2f%%", part * 100.0 / whole);
    }

    private static String count(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }
}
