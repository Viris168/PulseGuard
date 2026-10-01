package com.viris.PulseGuard.ai.tools;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One tool call, for the audit log saved with the answer and for the "Checked …" line the user
 * sees. {@code result} is a preview: what matters for debugging a wrong answer, not all of it.
 *
 * @param ok      false when the call was refused (limit), failed or timed out
 * @param sources help-doc sections the result showed the model, for the links under the answer
 *                (AI_MILESTONE_3.md); empty for every other tool
 */
public record ToolCallRecord(String tool, String arguments, String result, boolean ok, long durationMs,
                             List<Source> sources) {

    /** A help-doc section, e.g. "Slack alerts › Setting it up" at /docs/slack-alerts#setting-it-up. */
    public record Source(String title, String url) {
    }

    /** "[1] Slack alerts › Setting it up (/docs/slack-alerts#setting-it-up)": how a result lists a source. */
    private static final Pattern SOURCE_LINE = Pattern.compile("(?m)^\\[\\d+] (.+) \\((/docs/[a-z0-9-]+#[a-z0-9-]+)\\)$");

    public ToolCallRecord {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public ToolCallRecord(String tool, String arguments, String result, boolean ok, long durationMs) {
        this(tool, arguments, result, ok, durationMs, List.of());
    }

    /** The sources a tool result lists, in order. Read from the full result, before it is cut. */
    public static List<Source> sourcesIn(String result) {
        if (result == null) {
            return List.of();
        }
        Matcher m = SOURCE_LINE.matcher(result);
        java.util.ArrayList<Source> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(new Source(m.group(1).strip(), m.group(2)));
        }
        return List.copyOf(found);
    }
}
