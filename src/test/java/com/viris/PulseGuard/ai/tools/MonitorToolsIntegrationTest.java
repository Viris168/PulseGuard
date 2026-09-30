package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five tools against a real database. Two users both have a monitor named "Health"; the
 * other user's figures are deliberately different, so any leak would show in the numbers.
 */
@SpringBootTest
class MonitorToolsIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class TestBackends {
        @Bean
        @Primary
        TokenDenylist denylist() {
            return new InMemoryTokenDenylist();
        }

        @Bean
        @Primary
        LoginRateLimiter rateLimiter() {
            return new InMemoryLoginRateLimiter(5, 20);
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");

    @Autowired
    MonitorTools tools;
    @Autowired
    ToolGuard guard;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    CheckRepository checks;
    @Autowired
    IncidentRepository incidents;

    private Instant now;
    private LocalDate today;
    private LocalDate yesterday;
    private LocalDate twoDaysAgo;
    private Monitor health;
    private Monitor blog;
    private Monitor twinA;
    private Monitor twinB;
    private Monitor bobsHealth;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        now = Instant.now();
        today = LocalDate.now(ZONE);
        yesterday = today.minusDays(1);
        twoDaysAgo = today.minusDays(2);
        User alice = user("alice@example.com");
        User bob = user("bob@example.com");
        health = monitor(alice, "Health");
        blog = monitor(alice, "Blog");
        twinA = monitor(alice, "Twin");
        twinB = monitor(alice, "Twin");
        bobsHealth = monitor(bob, "Health");

        // Two days ago: 10 checks, 9 passed at 100 ms, one 500.
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                check(health, twoDaysAgo, 9, i, CheckResult.DOWN, 500, "Expected 200 but got 500", 80);
            } else {
                check(health, twoDaysAgo, 9, i, CheckResult.UP, 200, null, 100);
            }
        }
        // Yesterday: 5 passes at 200 ms, an outage 14:36–15:06 (three 503s), then 2 passes at 300 ms.
        for (int i = 0; i < 5; i++) {
            check(health, yesterday, 10, i, CheckResult.UP, 200, null, 200);
        }
        for (int minute : new int[]{36, 37, 38}) {
            check(health, yesterday, 14, minute, CheckResult.DOWN, 503, "Expected 200 but got 503", 90);
        }
        check(health, yesterday, 15, 5, CheckResult.UP, 200, null, 300);
        check(health, yesterday, 15, 6, CheckResult.UP, 200, null, 300);
        incident(health, at(yesterday, 14, 36), at(yesterday, 15, 6), "STATUS_MISMATCH: Expected 200 but got 503");

        // Bob's "Health": every check fails, and its incident says so. None of it may reach Alice.
        for (int i = 0; i < 10; i++) {
            check(bobsHealth, yesterday, 11, i, CheckResult.DOWN, 500, "BOB-SECRET failure", 50);
        }
        incident(bobsHealth, at(yesterday, 11, 0), at(yesterday, 12, 0), "BOB-SECRET cause");
    }

    @Test
    void uptimeForADateRangeWithAPerDayBreakdown() {
        String result = tools.getUptime("health", twoDaysAgo.toString(), yesterday.toString(), alice(7));

        assertThat(result).contains("Uptime of Health").contains("(Asia/Phnom_Penh)")
                .contains("80.00% up: 20 checks, 4 failed.")
                .contains("90.00% (10 checks, 1 failed)")
                .contains("70.00% (10 checks, 3 failed)");
    }

    @Test
    void responseTimesCountPassingChecksOnly() {
        String result = tools.getResponseTimes("Health", twoDaysAgo.toString(), yesterday.toString(), alice(7));

        // (9 × 100 + 5 × 200 + 2 × 300) / 16 passing checks = 156 ms.
        assertThat(result).contains("average 156 ms").contains("over 16 passing checks")
                .contains("Slowest day: ").contains("(average 229 ms)")
                .contains("Fastest day: ").contains("(average 100 ms)");
    }

    @Test
    void incidentsInARange() {
        String result = tools.getIncidents(twoDaysAgo.toString(), today.toString(), null, alice(7));

        assertThat(result).contains("1 incident on all shared monitors")
                .contains("Health: ").contains("14:36 to ").contains("15:06 (30m)")
                .contains("cause: STATUS_MISMATCH: Expected 200 but got 503")
                .doesNotContain("BOB-SECRET");
    }

    @Test
    void theLatestIncidentsFullStory() {
        String result = tools.getIncidentDetails("Health", null, alice(7));

        assertThat(result).contains("Incident on Health: started").contains("14:36").contains("(30m)")
                .contains("failed check: STATUS_MISMATCH: Expected 200 but got 503 (and 2 more like it)")
                .contains("check passed")
                .doesNotContain("BOB-SECRET");
    }

    @Test
    void anIncidentNearTheTimeTheModelGave() {
        String near = tools.getIncidentDetails("Health", yesterday + " 14:40", alice(7));
        String far = tools.getIncidentDetails("Health", twoDaysAgo + " 02:00", alice(7));

        assertThat(near).contains("Incident on Health: started");
        assertThat(far).contains("No incident on Health started near").contains("Recent ones started:");
    }

    @Test
    void recentFailuresNewestFirstAndLimited() {
        String result = tools.getRecentFailures("Health", 2, null, null, alice(7));

        assertThat(result).contains("Latest 2 failed checks of Health").contains("of 4 in the history your plan keeps");
        String[] lines = result.split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains("14:38").contains("HTTP 503").contains("STATUS_MISMATCH: Expected 200 but got 503");
        assertThat(lines[2]).contains("14:37");
    }

    @Test
    void failuresOnOneDayLeaveOutOtherDays() {
        String result = tools.getRecentFailures("Health", null, yesterday.toString(), yesterday.toString(), alice(7));

        String[] lines = result.split("\n");
        assertThat(lines[0]).startsWith("3 failed checks on Health, ").endsWith(", newest first:");
        assertThat(lines).hasSize(4);
        assertThat(result).contains("14:38").contains("14:36").doesNotContain("HTTP 500");
    }

    @Test
    void failuresOverSeveralDaysGiveTheTotalAndTheLatestFew() {
        String result = tools.getRecentFailures("Health", 2, twoDaysAgo.toString(), yesterday.toString(), alice(7));

        assertThat(result.split("\n")[0]).startsWith("4 failed checks on Health, ").endsWith("; the latest 2, newest first:");
        assertThat(result.split("\n")).hasSize(3);
    }

    @Test
    void failuresFromADayWithoutAnEndRunUpToToday() {
        String result = tools.getRecentFailures("Health", 10, twoDaysAgo.toString(), null, alice(7));

        assertThat(result).startsWith("4 failed checks on Health, ").contains("HTTP 500").contains("HTTP 503");
    }

    @Test
    void failuresOnAQuietDaySayThereWereNone() {
        String result = tools.getRecentFailures("Health", null, today.toString(), today.toString(), alice(7));

        assertThat(result).startsWith("No failed checks on Health, ");
    }

    @Test
    void failuresNeedTheFirstDayWhenGivenALastDay() {
        String result = tools.getRecentFailures("Health", null, null, yesterday.toString(), alice(7));

        assertThat(result).isEqualTo("Give the first day (from) as well, yyyy-MM-dd.");
    }

    @Test
    void failuresOlderThanThePlanKeepsAreRefused() {
        LocalDate old = today.minusDays(30);

        String result = tools.getRecentFailures("Health", null, old.toString(), old.toString(), alice(7));

        assertThat(result).startsWith("That range is older than the history this plan keeps (7 days");
    }

    @Test
    void failuresPastTheRawCheckHistorySayOlderOnesCantBeListed() {
        LocalDate longAgo = today.minusDays(200);

        String result = tools.getRecentFailures("Health", null, longAgo.toString(), yesterday.toString(), alice(365));

        assertThat(result).startsWith("4 failed checks on Health, ")
                .contains("Note: single checks are kept for 62 days, so older failures can't be listed");
    }

    @Test
    void anotherUsersMonitorWithTheSameNameIsNeverReached() {
        String result = tools.getUptime("Health", yesterday.toString(), yesterday.toString(), alice(7));

        assertThat(result).contains("\n70.00% up: 10 checks, 3 failed.").doesNotContain("\n0.00% up");
    }

    @Test
    void evenAScopeThatWronglyListsAnotherUsersMonitorCannotReachIt() {
        // A bug that put Bob's monitor id in Alice's scope still finds only Alice's own monitors:
        // the monitor list is loaded by her user id before the scope is applied.
        ToolContext buggy = context(new ToolScope(aliceId(), Set.of(health.getId(), bobsHealth.getId()), ZONE, 7, now));

        String result = tools.getIncidents(yesterday.toString(), today.toString(), null, buggy);

        assertThat(result).doesNotContain("BOB-SECRET");
    }

    @Test
    void anUnsharedMonitorIsNotFound() {
        String result = tools.getUptime("Blog", yesterday.toString(), today.toString(), alice(7));

        assertThat(result).startsWith("No monitor named Blog is shared with Ask AI.").contains("Shared monitors: ");
    }

    @Test
    void twoMonitorsWithTheSameNameAreNotGuessedBetween() {
        String result = tools.getUptime("Twin", yesterday.toString(), today.toString(), alice(7));

        assertThat(result).startsWith("Several shared monitors are named Twin").contains("Ask the user which one");
    }

    @Test
    void rangesAreClampedToThePlansHistory() {
        String clamped = tools.getUptime("Health", today.minusDays(30).toString(), today.toString(), alice(7));
        String tooOld = tools.getUptime("Health", today.minusDays(60).toString(), today.minusDays(40).toString(), alice(7));

        assertThat(clamped).contains("this plan keeps 7 days of history, so the range starts on");
        assertThat(tooOld).startsWith("That range is older than the history this plan keeps (7 days");
    }

    @Test
    void badArgumentsGetAMessageSayingWhatToFix() {
        assertThat(tools.getUptime("Health", "last week", "today", alice(7))).isEqualTo("Dates must be yyyy-MM-dd, for example 2026-09-01.");
        assertThat(tools.getUptime("Health", today.toString(), yesterday.toString(), alice(7))).contains("is after the last day");
        assertThat(tools.getUptime(" ", today.toString(), today.toString(), alice(7))).startsWith("Give the monitor's name");
    }

    @Test
    void theModelSeesFiveToolsWithTheirParameters() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder("get_uptime", "get_response_times", "get_incidents",
                        "get_incident_details", "get_recent_failures");
        String incidentsSchema = Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("get_incidents"))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        assertThat(incidentsSchema).contains("\"from\"").contains("\"to\"").contains("\"monitor\"");
    }

    @Test
    void worksEndToEndThroughTheGuardWithJsonArguments() {
        ToolRun run = guard.start(r -> { });
        ToolCallback uptime = run.guard(List.of(ToolCallbacks.from(tools))).stream()
                .filter(c -> c.getToolDefinition().name().equals("get_uptime")).findFirst().orElseThrow();

        String result = GuardedToolCallbackTest.fenced(uptime.call("{\"monitor\":\"Health\",\"from\":\"" + yesterday + "\",\"to\":\"" + yesterday + "\"}",
                alice(7)));

        assertThat(result).startsWith("<tool_result>").contains("70.00% up").endsWith("</tool_result>");
        assertThat(run.records()).singleElement().satisfies(r -> {
            assertThat(r.tool()).isEqualTo("get_uptime");
            assertThat(r.ok()).isTrue();
        });
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Alice's scope: Health and both Twins shared, Blog not. */
    private ToolContext alice(int historyDays) {
        return context(new ToolScope(aliceId(), Set.of(health.getId(), twinA.getId(), twinB.getId()), ZONE,
                historyDays, now));
    }

    private static ToolContext context(ToolScope scope) {
        return new ToolContext(scope.asToolContext());
    }

    private Long aliceId() {
        return users.findByEmail("alice@example.com").orElseThrow().getId();
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Test");
        user.setPasswordHash("x");
        user.setPlan(Plan.FREE);
        return users.save(user);
    }

    private Monitor monitor(User owner, String name) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName(name);
        monitor.setUrl("https://example.com/" + name);
        return monitors.save(monitor);
    }

    private Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private void check(Monitor monitor, LocalDate day, int hour, int minute, CheckResult result, int status,
                       String error, int responseMs) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(result);
        check.setStatusCode(status);
        check.setResponseTimeMs(responseMs);
        if (error != null) {
            check.setErrorType(ErrorType.STATUS_MISMATCH);
            check.setErrorMessage(error);
        }
        check.setCheckedAt(at(day, hour, minute));
        checks.save(check);
    }

    private void incident(Monitor monitor, Instant started, Instant resolved, String cause) {
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setStartedAt(started);
        incident.setResolvedAt(resolved);
        incident.setCause(cause);
        incidents.save(incident);
    }
}
