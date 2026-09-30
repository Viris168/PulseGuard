package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help docs are what Ask AI will quote (AI_MILESTONE_3.md), so the facts in them must match
 * the code. A docs change that contradicts the code, or a code change that forgets the docs,
 * fails here.
 */
class HelpDocsFactsTest {

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\n(.*?)\\n---\\n", Pattern.DOTALL);

    private final PlanLimits limits = new PlanLimits();

    @Test
    void everyArticleHasATitleASummaryAndSectionsThatStandAlone() throws IOException {
        Resource[] articles = new PathMatchingResourcePatternResolver().getResources("classpath:help/*.md");
        assertThat(articles).isNotEmpty();
        for (Resource article : articles) {
            String text = article.getContentAsString(StandardCharsets.UTF_8);
            String name = article.getFilename();
            Matcher front = FRONT_MATTER.matcher(text);
            assertThat(front.find()).as("%s starts with front matter", name).isTrue();
            assertThat(front.group(1)).as(name).containsPattern("(?m)^title: \\S")
                    .containsPattern("(?m)^summary: \\S").containsPattern("(?m)^describes: \\S");
            String body = text.substring(front.end());
            assertThat(body).as("%s has no # heading: the title comes from front matter", name)
                    .doesNotContainPattern("(?m)^# ");
            List<String> sections = sections(body);
            assertThat(sections).as("%s has ## sections", name).isNotEmpty();
            sections.forEach(s -> assertThat(s.lines().skip(1).collect(Collectors.joining()).strip())
                    .as("%s: section '%s' has text", name, s.lines().findFirst().orElse("")).isNotEmpty());
        }
    }

    @Test
    void thePlanTableMatchesPlanLimits() throws IOException {
        String doc = article("plans-and-limits");
        for (Plan plan : Plan.values()) {
            int column = plan.ordinal();
            PlanLimits.Limits l = limits.forPlan(plan);
            assertThat(cell(doc, "Monitors", column)).as("%s monitors", plan)
                    .isEqualTo(l.maxMonitors() == PlanLimits.UNLIMITED ? "Unlimited" : String.valueOf(l.maxMonitors()));
            assertThat(cell(doc, "Fastest check interval", column)).as("%s interval", plan)
                    .isEqualTo(minutes(l.minIntervalSeconds()));
            assertThat(cell(doc, "History", column)).as("%s history", plan).isEqualTo(l.retentionDays() + " days");
            assertThat(cell(doc, "Alert channels", column)).as("%s channels", plan).isEqualTo(
                    l.channels().stream().sorted().map(HelpDocsFactsTest::channelName).collect(Collectors.joining(", ")));
            assertThat(cell(doc, "Ask AI questions per day", column)).as("%s Ask AI", plan).startsWith(
                    l.aiDailyQuestions() == PlanLimits.UNLIMITED ? "Unlimited" : String.valueOf(l.aiDailyQuestions()));
        }
    }

    @Test
    void historyAndFairUseNumbersMatchTheSettings() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        String rawDays = find(yaml, "raw-check-days: (\\d+)");
        String fairUse = find(yaml, "fair-use-daily-questions: \\$\\{PULSEGUARD_AI_FAIR_USE_DAILY_QUESTIONS:(\\d+)}");

        String plans = article("plans-and-limits");
        assertThat(plans).contains("never more than **" + rawDays + " days**").contains("fair use: " + fairUse + ")")
                .contains("fair-use cap of " + fairUse + " questions");
        assertThat(article("how-incidents-work")).contains("up to " + rawDays + " days on Pro and Business");
    }

    @Test
    void theIncidentRulesMatchTheThresholds() throws IOException {
        String env = Files.readString(Path.of(".env.example"));
        String failures = find(env, "PULSEGUARD_INCIDENT_FAILURE_THRESHOLD=(\\d+)");
        String passes = find(env, "PULSEGUARD_INCIDENT_RECOVERY_THRESHOLD=(\\d+)");

        String doc = article("how-incidents-work");
        assertThat(doc).contains("**" + failures + " checks in a row failed**")
                .contains("**" + passes + " checks in a row passed**");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String article(String slug) throws IOException {
        return new PathMatchingResourcePatternResolver().getResource("classpath:help/" + slug + ".md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> sections(String body) {
        List<String> sections = new ArrayList<>();
        for (String part : ("\n" + body).split("\n(?=## )")) {
            if (part.startsWith("## ")) {
                sections.add(part);
            }
        }
        return sections;
    }

    /** The table cell in the row whose first cell is {@code row}; column 0 is Free. */
    private static String cell(String doc, String row, int column) {
        return doc.lines().filter(l -> l.startsWith("| " + row + " |")).findFirst()
                .map(l -> l.split("\\|")[column + 2].strip())
                .orElseThrow(() -> new AssertionError("No row '" + row + "' in the plan table"));
    }

    private static String minutes(int seconds) {
        int m = seconds / 60;
        return m == 1 ? "1 minute" : m + " minutes";
    }

    private static String channelName(ChannelType type) {
        String name = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("'%s' found", regex).isTrue();
        return m.group(1);
    }
}
