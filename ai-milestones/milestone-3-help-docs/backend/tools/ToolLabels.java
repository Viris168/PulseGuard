package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.ai.PromptText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The line a person sees under an answer for each lookup, e.g. "Checked uptime for Health,
 * 2026-09-01 to 2026-09-03". Built from the tool's name and arguments, so it says what was
 * actually looked up, which is what makes a wrong answer easy to spot.
 */
public final class ToolLabels {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolLabels() {
    }

    public static String label(ToolCallRecord record) {
        JsonNode args = arguments(record.arguments());
        String monitor = PromptText.clip(text(args, "monitor"), 60);
        String range = range(text(args, "from"), text(args, "to"));
        String label = switch (record.tool()) {
            case "get_uptime" -> "Checked uptime for " + orUnnamed(monitor) + range;
            case "get_response_times" -> "Checked response times for " + orUnnamed(monitor) + range;
            case "get_incidents" -> "Checked incidents for " + (monitor.isEmpty() ? "all monitors" : monitor) + range;
            case "get_incident_details" -> "Checked the incident on " + orUnnamed(monitor)
                    + (text(args, "start").isEmpty() ? "" : " starting " + PromptText.clip(text(args, "start"), 20));
            case "search_help_docs" -> "Searched the help docs for \"" + PromptText.clip(text(args, "query"), 60) + "\"";
            case "get_recent_failures" -> range.isEmpty()
                    ? "Checked recent failed checks for " + orUnnamed(monitor)
                    : "Checked failed checks for " + orUnnamed(monitor) + range;
            default -> "Checked " + PromptText.clip(record.tool(), 50);
        };
        if (record.ok()) {
            return label;
        }
        return label + (record.result() != null && record.result().startsWith("Tool limit")
                ? " (skipped: lookup limit reached)"
                : " (couldn't check)");
    }

    private static String range(String from, String to) {
        if (from.isEmpty()) {
            return "";
        }
        return ", " + PromptText.clip(from, 12) + (to.isEmpty() || to.equals(from) ? "" : " to " + PromptText.clip(to, 12));
    }

    private static String orUnnamed(String monitor) {
        return monitor.isEmpty() ? "a monitor" : monitor;
    }

    private static JsonNode arguments(String json) {
        try {
            return JSON.readTree(json == null ? "{}" : json);
        } catch (RuntimeException e) {
            return JSON.createObjectNode(); // cut or malformed: label without the details
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args == null ? null : args.get(field);
        return value == null || value.isNull() ? "" : PromptText.oneLine(value.asString());
    }
}
