package com.viris.PulseGuard.ai.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The "Checked …" line a person sees under an answer says what was actually looked up. */
class ToolLabelsTest {

    @Test
    void uptimeNamesTheMonitorAndTheRange() {
        assertThat(label("get_uptime", "{\"monitor\":\"Health\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}"))
                .isEqualTo("Checked uptime for Health, 2026-09-01 to 2026-09-30");
    }

    @Test
    void failuresWithoutDatesAreTheRecentOnes() {
        assertThat(label("get_recent_failures", "{\"monitor\":\"Health\"}"))
                .isEqualTo("Checked recent failed checks for Health");
    }

    @Test
    void failuresForADayNameTheDay() {
        assertThat(label("get_recent_failures", "{\"monitor\":\"Health\",\"from\":\"2026-09-29\",\"to\":\"2026-09-29\"}"))
                .isEqualTo("Checked failed checks for Health, 2026-09-29");
    }

    @Test
    void failuresForARangeNameTheRange() {
        assertThat(label("get_recent_failures", "{\"monitor\":\"Health\",\"from\":\"2026-09-28\",\"to\":\"2026-09-30\"}"))
                .isEqualTo("Checked failed checks for Health, 2026-09-28 to 2026-09-30");
    }

    @Test
    void aHelpDocsSearchQuotesWhatItSearchedFor() {
        assertThat(label("search_help_docs", "{\"query\":\"slack alerts setup\"}"))
                .isEqualTo("Searched the help docs for \"slack alerts setup\"");
    }

    @Test
    void aFailedLookupSaysItCouldNotCheck() {
        ToolCallRecord failed = new ToolCallRecord("get_recent_failures", "{\"monitor\":\"Health\"}",
                GuardedToolCallback.FAILED, false, 3);

        assertThat(ToolLabels.label(failed)).isEqualTo("Checked recent failed checks for Health (couldn't check)");
    }

    private static String label(String tool, String arguments) {
        return ToolLabels.label(new ToolCallRecord(tool, arguments, "ok", true, 5));
    }
}
