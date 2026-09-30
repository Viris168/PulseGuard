package com.viris.PulseGuard.ai.help;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the Markdown help docs into articles, sections and search chunks. */
class HelpArticlesTest {

    @Test
    void splitsAnArticleIntoItsIntroAndSections() {
        HelpArticles.Article a = HelpArticles.parse("slack-alerts", """
                ---
                title: Slack alerts
                summary: Alerts in Slack.
                describes: notification/channels/SlackSender.java
                ---

                Intro text.

                ## Setting it up

                1. Create a webhook.
                2. Paste it.

                ## After a downgrade

                Switched off.
                """);

        assertThat(a.slug()).isEqualTo("slack-alerts");
        assertThat(a.title()).isEqualTo("Slack alerts");
        assertThat(a.summary()).isEqualTo("Alerts in Slack.");
        assertThat(a.intro()).isEqualTo("Intro text.");
        assertThat(a.sections()).extracting(HelpArticles.Section::heading).containsExactly("Setting it up", "After a downgrade");
        assertThat(a.sections().getFirst().anchor()).isEqualTo("setting-it-up");
        assertThat(a.sections().getFirst().content()).isEqualTo("1. Create a webhook.\n2. Paste it.");
    }

    @Test
    void anchorsAreLowercaseWordsJoinedByDashes() {
        assertThat(HelpArticles.anchor("What counts as a failed check")).isEqualTo("what-counts-as-a-failed-check");
        assertThat(HelpArticles.anchor("TIMEOUT: no answer in time")).isEqualTo("timeout-no-answer-in-time");
        assertThat(HelpArticles.anchor("3xx: redirects")).isEqualTo("3xx-redirects");
        assertThat(HelpArticles.anchor("Card, invoices and receipts")).isEqualTo("card-invoices-and-receipts");
        assertThat(HelpArticles.anchor("Addresses you can't monitor")).isEqualTo("addresses-you-cant-monitor");
        assertThat(HelpArticles.anchor("Why it’s late")).isEqualTo("why-its-late");
    }

    @Test
    void anArticleWithoutFrontMatterIsRejected() {
        assertThatThrownBy(() -> HelpArticles.parse("broken", "## Just a section\n\ntext"))
                .hasMessageContaining("broken");
    }

    @Test
    void readsEveryRealArticleWithUniqueAnchorsAndTextInEverySection() {
        HelpArticles docs = new HelpArticles();

        assertThat(docs.all()).hasSize(16);
        for (HelpArticles.Article a : docs.all()) {
            Set<String> anchors = new HashSet<>();
            for (HelpArticles.Section s : a.sections()) {
                assertThat(anchors.add(s.anchor())).as("%s: anchor '%s' is unique", a.slug(), s.anchor()).isTrue();
                assertThat(s.content()).as("%s#%s has text", a.slug(), s.anchor()).isNotBlank();
            }
        }
        assertThat(docs.chunks()).allSatisfy(c -> assertThat(c.embeddingText()).startsWith(c.title() + " › " + c.heading()));
    }
}
