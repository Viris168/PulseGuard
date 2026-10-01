package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.ai.tools.PlainTextResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Ask AI's help-docs tool (AI_MILESTONE_3.md, Step 5). Unlike the monitor tools it reads nothing
 * of the user's: the docs are the same for everyone, so it needs no ToolScope. Offered to the
 * model only when an embedding model is configured (ChatService).
 *
 * <p>Each section is listed as {@code [n] Title › Heading (/docs/article#anchor)}: the model cites
 * "[n]", and ToolCallRecord reads the same lines back as the sources shown under the answer.
 */
@Component
public class HelpDocsTools {

    static final String NOTHING = "Nothing in the help docs matches that. Say the help docs don't cover it, and "
            + "suggest contacting support.";

    private final HelpDocsSearch search;

    public HelpDocsTools(HelpDocsSearch search) {
        this.search = search;
    }

    @Tool(name = "search_help_docs", resultConverter = PlainTextResult.class, description = """
            Search PulseGuard's own help docs: how the product works, setting things up, monitors, \
            alerts, incidents, status pages, plans, billing, API keys, the account, and what check \
            errors and status codes mean. Not for the user's own monitor data: use the other tools \
            for that. Returns the best matching sections, each numbered for citing.""")
    public String searchHelpDocs(
            @ToolParam(description = "What to look for, in a few words, e.g. \"slack alerts setup\" or \"what 502 means\"")
            String query) {
        if (query == null || query.isBlank()) {
            return "Give a few words to search for.";
        }
        List<HelpDocsSearch.Hit> hits = search.search(query.strip());
        if (hits.isEmpty()) {
            return NOTHING;
        }
        List<String> parts = new ArrayList<>();
        parts.add("Help doc sections for \"" + query.strip() + "\", best first. Answer only from those that "
                + "actually answer the question; if none do, say the help docs don't cover it.");
        for (int i = 0; i < hits.size(); i++) {
            HelpDocsSearch.Hit h = hits.get(i);
            parts.add("[" + (i + 1) + "] " + h.title() + " › " + h.heading() + " (" + h.url() + ")\n" + h.content());
        }
        return String.join("\n\n", parts);
    }
}
