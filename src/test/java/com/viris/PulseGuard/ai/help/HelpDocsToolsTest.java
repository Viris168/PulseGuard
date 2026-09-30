package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.ai.tools.ToolCallRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What search_help_docs tells the model, and that its source lines can be read back. */
class HelpDocsToolsTest {

    private final HelpDocsSearch search = mock(HelpDocsSearch.class);
    private final HelpDocsTools tools = new HelpDocsTools(search);

    @Test
    void listsEachSectionNumberedWithItsTitleLinkAndText() {
        when(search.search("slack setup")).thenReturn(List.of(
                new HelpDocsSearch.Hit("slack-alerts", "Slack alerts", "Setting it up", "setting-it-up",
                        "Create an incoming webhook.", 0.84, 0.03),
                new HelpDocsSearch.Hit("getting-started", "Getting started", "Get alerted", "get-alerted",
                        "Every account gets an email channel.", 0.81, 0.02)));

        String result = tools.searchHelpDocs("  slack setup ");

        assertThat(result).startsWith("Help doc sections for \"slack setup\", best first.")
                .contains("[1] Slack alerts › Setting it up (/docs/slack-alerts#setting-it-up)\nCreate an incoming webhook.")
                .contains("[2] Getting started › Get alerted (/docs/getting-started#get-alerted)\nEvery account gets");
        assertThat(ToolCallRecord.sourcesIn(result)).containsExactly(
                new ToolCallRecord.Source("Slack alerts › Setting it up", "/docs/slack-alerts#setting-it-up"),
                new ToolCallRecord.Source("Getting started › Get alerted", "/docs/getting-started#get-alerted"));
    }

    @Test
    void saysSoWhenNothingMatches() {
        when(search.search("capital of france")).thenReturn(List.of());

        assertThat(tools.searchHelpDocs("capital of france")).isEqualTo(HelpDocsTools.NOTHING);
        assertThat(ToolCallRecord.sourcesIn(HelpDocsTools.NOTHING)).isEmpty();
    }

    @Test
    void asksForWordsWhenTheQueryIsEmpty() {
        assertThat(tools.searchHelpDocs("  ")).isEqualTo("Give a few words to search for.");
    }

    @Test
    void onlyWellFormedSourceLinesCount() {
        assertThat(ToolCallRecord.sourcesIn("""
                [1] Real › Section (/docs/real#section)
                text mentioning (/docs/fake#link) in passing
                [2] Elsewhere (https://evil.example/docs/x#y)
                """)).containsExactly(new ToolCallRecord.Source("Real › Section", "/docs/real#section"));
    }
}
