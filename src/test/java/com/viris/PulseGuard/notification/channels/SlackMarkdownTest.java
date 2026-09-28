package com.viris.PulseGuard.notification.channels;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackMarkdownTest {

    @Test
    void escapesTheThreeControlCharacters() {
        assertThat(SlackMarkdown.escape("<!channel> & <@U1>")).isEqualTo("&lt;!channel&gt; &amp; &lt;@U1&gt;");
    }

    @Test
    void escapesAmpersandsOnlyOnce() {
        assertThat(SlackMarkdown.escape("a &lt; b")).isEqualTo("a &amp;lt; b");
    }

    @Test
    void leavesOrdinaryTextAndNullSafe() {
        assertThat(SlackMarkdown.escape("HTTP 500: *boom* _now_")).isEqualTo("HTTP 500: *boom* _now_");
        assertThat(SlackMarkdown.escape(null)).isEmpty();
    }
}
