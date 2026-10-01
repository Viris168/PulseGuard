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

        String plans = flat(article("plans-and-limits"));
        assertThat(plans).contains("never more than **" + rawDays + " days**").contains("fair use: " + fairUse + ")")
                .contains("fair-use cap of " + fairUse + " questions");
        assertThat(flat(article("how-incidents-work"))).contains("up to " + rawDays + " days on Pro and Business");
    }

    @Test
    void theIncidentRulesMatchTheThresholds() throws IOException {
        String env = Files.readString(Path.of(".env.example"));
        String failures = find(env, "PULSEGUARD_INCIDENT_FAILURE_THRESHOLD=(\\d+)");
        String passes = find(env, "PULSEGUARD_INCIDENT_RECOVERY_THRESHOLD=(\\d+)");

        String doc = flat(article("how-incidents-work"));
        assertThat(doc).contains("**" + failures + " checks in a row failed**")
                .contains("**" + passes + " checks in a row passed**");
    }

    @Test
    void theStatusesInGettingStartedAreTheLabelsTheAppShows() throws IOException {
        String badge = Files.readString(Path.of("frontend/src/components/ui/StatusBadge.tsx"));
        Matcher label = Pattern.compile("label: '([^']+)'").matcher(badge);
        List<String> labels = new ArrayList<>();
        while (label.find()) {
            labels.add(label.group(1));
        }
        assertThat(labels).hasSize(6);

        String doc = article("getting-started");
        labels.forEach(l -> assertThat(doc).as("getting-started explains '%s'", l).contains("- **" + l + "**: "));
    }

    @Test
    void httpMonitorLimitsMatchTheValidator() throws IOException {
        String validator = source("monitor/dto/MonitorRequestValidator.java");
        String doc = flat(article("http-monitors"));
        assertThat(doc).contains("list up to " + constant(validator, "MAX_STATUSES") + " codes")
                .contains("up to " + constant(validator, "MAX_HEADERS") + " request headers")
                .contains("up to " + thousands(constant(validator, "MAX_HEADER_VALUE")) + " characters, on one line")
                .contains("up to " + thousands(constant(validator, "MAX_BODY")) + " characters");
        assertThat(validator).contains("\"Timeout must not exceed 30000 ms\"").contains("\"Timeout must be at least 1000 ms\"");
        assertThat(doc).contains("from 1 to 30 seconds");
        assertThat(flat(article("status-codes"))).contains("You can list up to " + constant(validator, "MAX_STATUSES") + ".");
    }

    @Test
    void theUserAgentInTheDocsIsTheOneChecksSend() throws IOException {
        String userAgent = find(Files.readString(Path.of(".env.example")), "PULSEGUARD_CHECK_USER_AGENT=(.+)").strip();
        for (String slug : List.of("http-monitors", "troubleshooting-checks", "status-codes")) {
            assertThat(flat(article(slug))).as(slug).contains(userAgent);
        }
    }

    @Test
    void alertAndAccountNumbersMatchTheCode() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(find(yaml, "backoff: \\$\\{PULSEGUARD_ALERT_RETRY_BACKOFF:([^}]+)}")).isEqualTo("1m,5m,30m");
        assertThat(flat(article("alert-delivery"))).contains("3 more times: after 1 minute, 5 minutes and 30 minutes");

        String verify = find(yaml, "verify-token-ttl: \\$\\{PULSEGUARD_EMAIL_VERIFY_TTL:([^}]+)}");
        String reset = find(yaml, "reset-token-ttl: \\$\\{PULSEGUARD_PASSWORD_RESET_TTL:([^}]+)}");
        assertThat(verify).isEqualTo("24h");
        assertThat(reset).isEqualTo("30m");
        assertThat(flat(article("email-alerts"))).contains("The link works for 24 hours")
                .contains("up to " + constant(source("notification/ChannelService.java"), "MAX_CHANNELS") + " alert channels");
        assertThat(flat(article("account"))).contains("it works for 24 hours").contains("works for **30 minutes**")
                .contains("8 to 72 characters");
        assertThat(source("auth/dto/ChangePasswordRequest.java")).contains("@Size(min = 8, max = 72");

        String keys = flat(article("api-keys"));
        assertThat(keys).contains("up to " + constant(source("apikey/ApiKeyService.java"), "MAX_KEYS") + " keys")
                .contains("(up to 50 characters)");
        assertThat(source("apikey/dto/ApiKeyRequest.java")).contains("@Size(max = 50");
    }

    @Test
    void statusPageAndHeartbeatNumbersMatchTheCode() throws IOException {
        String request = source("statuspage/dto/StatusPageRequest.java");
        assertThat(request).contains("@Size(max = 80").contains("@Size(max = 280").contains("@Size(max = 100");
        String publicPage = source("statuspage/PublicStatusPageService.java");
        assertThat(publicPage).contains("MAX_HISTORY_DAYS = 90").contains("INCIDENT_WINDOW = Duration.ofDays(14)");
        assertThat(flat(article("status-pages"))).contains("(up to 80 characters)").contains("(up to 280)")
                .contains("Up to 100 monitors").contains("last 14 days").contains("up to 90 days");

        assertThat(source("heartbeat/HeartbeatService.java")).contains("MIN_SPACING = Duration.ofSeconds(10)");
        assertThat(flat(article("heartbeat-monitors"))).contains("less than 10 seconds apart");
    }

    @Test
    void askAiLimitsMatchTheCode() throws IOException {
        String doc = flat(article("ask-ai"));
        for (Plan plan : Plan.values()) {
            int questions = limits.aiDailyQuestions(plan);
            String name = plan.name().charAt(0) + plan.name().substring(1).toLowerCase(Locale.ROOT);
            assertThat(doc).as(plan.name()).contains("| " + name + " | "
                    + (questions == PlanLimits.UNLIMITED ? "Unlimited" : String.valueOf(questions)));
        }
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(doc).contains("at most " + find(yaml, "max-tool-calls: \\$\\{PULSEGUARD_AI_MAX_TOOL_CALLS:(\\d+)}")
                + " lookups per question");
        assertThat(doc).contains("up to " + find(source("ai/chat/ChatService.java"), "MAX_MESSAGES = (\\d+)") + " messages");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String source(String path) throws IOException {
        return Files.readString(Path.of("src/main/java/com/viris/PulseGuard/" + path));
    }

    /** A {@code static final int NAME = 10_000;} constant's value. */
    private static int constant(String source, String name) {
        return Integer.parseInt(find(source, name + " = ([\\d_]+);").replace("_", ""));
    }

    /** 2000 → "2,000", as the docs write numbers. */
    private static String thousands(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String article(String slug) throws IOException {
        return new PathMatchingResourcePatternResolver().getResource("classpath:help/" + slug + ".md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** The text with line breaks as spaces: wrapping a Markdown line never changes what it says. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
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
