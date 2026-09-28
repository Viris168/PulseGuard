package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.incident.IncidentQueryService;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.statuspage.PublicStatusPageService;
import com.viris.PulseGuard.statuspage.dto.PublicDay;
import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Rollup and retention against a real Postgres, with checks written at chosen ages. The batch
 * size is 2, so every delete below also goes round the batching loop.
 */
@SpringBootTest(properties = "pulseguard.housekeeping.batch-size=2")
class HousekeepingIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

    /** Midday, so "N days ago" never straddles a UTC midnight by accident. */
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    CheckRepository checks;
    @Autowired
    CheckDailyStatRepository dailyStats;
    @Autowired
    DailyRollupService rollup;
    @Autowired
    RetentionService retention;
    @Autowired
    HousekeepingService housekeeping;
    @Autowired
    HousekeepingRegistrar registrar;
    @Autowired
    HousekeepingProperties properties;
    @Autowired
    Scheduler scheduler;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    PublicStatusPageService statusPages;
    @Autowired
    IncidentQueryService incidents;

    @BeforeEach
    void setUp() {
        users.deleteAll(); // monitors, checks, pings, summaries, pages follow by ON DELETE CASCADE
    }

    // --- helpers ----------------------------------------------------------

    private User user(String email, Plan plan) {
        return users.save(User.builder().name("Owner").email(email).passwordHash("hash").plan(plan).build());
    }

    private Monitor monitor(User owner) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("API");
        monitor.setUrl("https://example.com/health");
        return monitors.save(monitor);
    }

    private void check(Monitor monitor, Instant at, String result, Integer responseMs) {
        jdbc.update("insert into checks (monitor_id, result, response_time_ms, checked_at) values (?, ?, ?, ?)",
                monitor.getId(), result, responseMs, Timestamp.from(at));
    }

    private void up(Monitor monitor, Instant at) {
        check(monitor, at, "UP", 100);
    }

    private void ping(Monitor monitor, Instant at) {
        jdbc.update("insert into pings (monitor_id, received_at, source_ip) values (?, ?, '203.0.113.7')",
                monitor.getId(), Timestamp.from(at));
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private List<Instant> checkTimes(Monitor monitor) {
        return jdbc.queryForList("select checked_at from checks where monitor_id = ? order by checked_at",
                Timestamp.class, monitor.getId()).stream().map(Timestamp::toInstant).toList();
    }

    private List<LocalDate> summaryDays(Monitor monitor) {
        return jdbc.queryForList("select day from check_daily_stats where monitor_id = ? order by day",
                java.sql.Date.class, monitor.getId()).stream().map(java.sql.Date::toLocalDate).toList();
    }

    // --- rollup -----------------------------------------------------------

    @Test
    void rollupSummarisesOneUtcDayOverPassingChecksOnly() {
        Monitor m = monitor(user("a@example.com", Plan.PRO));
        Instant yesterday = TODAY.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        check(m, yesterday, "UP", 100);                       // 00:00:00 belongs to yesterday
        check(m, yesterday.plusSeconds(3600), "UP", 200);
        check(m, yesterday.plusSeconds(7200), "UP", 300);
        check(m, yesterday.plusSeconds(86_399), "DOWN", 9000); // 23:59:59, slow and failed
        check(m, yesterday.plusSeconds(86_400), "UP", 999);    // 00:00:00 today: not summarised

        rollup.rollUp(TODAY);

        CheckDailyStat stat = dailyStats.findById(new CheckDailyStatId(m.getId(), TODAY.minusDays(1))).orElseThrow();
        assertThat(stat.getTotalChecks()).isEqualTo(4);
        assertThat(stat.getFailedChecks()).isEqualTo(1);
        assertThat(stat.getAvgResponseMs()).isEqualTo(200);   // the DOWN check's 9000 ms is left out
        assertThat(stat.getP95ResponseMs()).isEqualTo(290);   // exact 95th percentile of 100, 200, 300
        // The same numbers as the dashboard's own query for that day.
        var window = checks.windowTotals(m.getId(), yesterday, yesterday.plusSeconds(86_400));
        assertThat(window.getTotal()).isEqualTo(4);
        assertThat(window.getP95Ms()).isCloseTo(290.0, within(0.5));
        assertThat(summaryDays(m)).containsExactly(TODAY.minusDays(1));
    }

    @Test
    void rollupCanRunAgainAndPicksUpLateChecks() {
        Monitor m = monitor(user("a@example.com", Plan.PRO));
        up(m, daysAgo(1));
        rollup.rollUp(TODAY);
        rollup.rollUp(TODAY);
        CheckDailyStatId id = new CheckDailyStatId(m.getId(), TODAY.minusDays(1));
        assertThat(dailyStats.findById(id).orElseThrow().getTotalChecks()).isEqualTo(1);

        up(m, daysAgo(1).plusSeconds(60)); // a check saved after last night's run
        rollup.rollUp(TODAY);

        assertThat(dailyStats.findById(id).orElseThrow().getTotalChecks()).isEqualTo(2);
    }

    @Test
    void firstRunBackfillsEveryDayWithChecksUpToYesterday() {
        Monitor m = monitor(user("a@example.com", Plan.PRO));
        up(m, daysAgo(10));
        up(m, daysAgo(5));
        up(m, daysAgo(1));
        up(m, NOW); // today: not yet

        LocalDate through = rollup.rollUp(TODAY);

        assertThat(through).isEqualTo(TODAY.minusDays(1));
        assertThat(summaryDays(m)).containsExactly(TODAY.minusDays(10), TODAY.minusDays(5), TODAY.minusDays(1));
    }

    // --- retention --------------------------------------------------------

    @Test
    void eachPlanKeepsItsOwnHistory() {
        Monitor free = monitor(user("free@example.com", Plan.FREE));
        Monitor pro = monitor(user("pro@example.com", Plan.PRO));
        Monitor business = monitor(user("business@example.com", Plan.BUSINESS));
        for (Monitor m : List.of(free, pro, business)) {
            for (int days : new int[]{100, 70, 40, 10, 3}) {
                up(m, daysAgo(days));
            }
        }

        housekeeping.run(NOW);

        // Raw: Free 7 days; Pro and Business capped at raw-check-days (62).
        assertThat(checkTimes(free)).containsExactly(daysAgo(3));
        assertThat(checkTimes(pro)).containsExactly(daysAgo(40), daysAgo(10), daysAgo(3));
        assertThat(checkTimes(business)).containsExactly(daysAgo(40), daysAgo(10), daysAgo(3));
        // Summaries: Free 7 days, Pro 90, Business 365.
        assertThat(summaryDays(free)).containsExactly(TODAY.minusDays(3));
        assertThat(summaryDays(pro)).containsExactly(
                TODAY.minusDays(70), TODAY.minusDays(40), TODAY.minusDays(10), TODAY.minusDays(3));
        assertThat(summaryDays(business)).containsExactly(TODAY.minusDays(100),
                TODAY.minusDays(70), TODAY.minusDays(40), TODAY.minusDays(10), TODAY.minusDays(3));
    }

    @Test
    void rawChecksFromDaysNotYetSummarisedAreNeverDeleted() {
        Monitor free = monitor(user("free@example.com", Plan.FREE));
        up(free, daysAgo(25));
        up(free, daysAgo(15));

        retention.purge(TODAY.minusDays(20), NOW); // summarised only through 20 days ago

        assertThat(checkTimes(free)).containsExactly(daysAgo(15)); // older than 7 days, but not summarised

        retention.purge(null, NOW); // nothing summarised at all
        assertThat(checkTimes(free)).containsExactly(daysAgo(15));
    }

    @Test
    void pingsAreTrimmedLikeRawChecks() {
        Monitor free = monitor(user("free@example.com", Plan.FREE));
        Monitor pro = monitor(user("pro@example.com", Plan.PRO));
        for (int days : new int[]{70, 10, 3}) {
            ping(free, daysAgo(days));
            ping(pro, daysAgo(days));
        }

        RetentionService.Result result = housekeeping.run(NOW);

        assertThat(jdbc.queryForObject("select count(*) from pings where monitor_id = ?", Long.class, free.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pings where monitor_id = ?", Long.class, pro.getId()))
                .isEqualTo(2);
        assertThat(result.pings()).isEqualTo(3);
    }

    @Test
    void downgradeToFreeTrimsHistoryOnTheNextRun() {
        User owner = user("a@example.com", Plan.PRO);
        Monitor m = monitor(owner);
        up(m, daysAgo(30));
        up(m, daysAgo(3));
        housekeeping.run(NOW);
        assertThat(checkTimes(m)).hasSize(2);

        owner.setPlan(Plan.FREE);
        users.save(owner);
        housekeeping.run(NOW);

        assertThat(checkTimes(m)).containsExactly(daysAgo(3));
        assertThat(summaryDays(m)).containsExactly(TODAY.minusDays(3));
    }

    // --- readers after retention --------------------------------------------

    @Test
    void statusPageKeepsItsLongHistoryAfterRawChecksAreGone() {
        User owner = user("pro@example.com", Plan.PRO);
        Monitor m = monitor(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant midday = today.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(12));
        check(m, midday.minus(Duration.ofDays(80)), "UP", 100);
        check(m, midday.minus(Duration.ofDays(80)).plusSeconds(60), "DOWN", null);
        up(m, midday.minus(Duration.ofDays(1)));
        up(m, now.minusSeconds(60)); // today, from raw checks
        jdbc.update("insert into status_pages (user_id, slug, title, published) values (?, 'shop', 'Shop', true)",
                owner.getId());
        jdbc.update("insert into status_page_monitors (status_page_id, monitor_id, display_name, position) "
                + "select id, ?, 'API', 0 from status_pages where slug = 'shop'", m.getId());

        housekeeping.run(now);
        assertThat(checkTimes(m)).noneMatch(t -> t.isBefore(now.minus(Duration.ofDays(62))));

        PublicStatusPageResponse page = statusPages.render("shop");
        Map<LocalDate, Double> uptime = page.components().getFirst().days().stream()
                .filter(d -> d.uptimePct() != null)
                .collect(java.util.stream.Collectors.toMap(
                        d -> d.date().atZone(ZoneOffset.UTC).toLocalDate(), PublicDay::uptimePct));
        assertThat(page.historyDays()).isEqualTo(90);
        assertThat(uptime).containsEntry(today.minusDays(80), 50.0) // from the daily summary
                .containsEntry(today.minusDays(1), 100.0)
                .containsEntry(today, 100.0);                        // from raw checks
    }

    @Test
    void incidentOlderThanRawHistoryStillOpens() {
        User owner = user("pro@example.com", Plan.PRO);
        Monitor m = monitor(owner);
        Long incidentId = jdbc.queryForObject("insert into incidents (monitor_id, status, cause, started_at, resolved_at) "
                + "values (?, 'RESOLVED', 'STATUS_MISMATCH', ?, ?) returning id", Long.class,
                m.getId(), Timestamp.from(daysAgo(70)), Timestamp.from(daysAgo(70).plusSeconds(600)));

        housekeeping.run(NOW);

        assertThat(incidents.detail(owner.getId(), incidentId)).isNotNull();
    }

    // --- scheduling ----------------------------------------------------------

    @Test
    void nightlyJobIsRegisteredOnceOnUtcCron() throws Exception {
        registrar.run(null);
        registrar.run(null); // a second startup replaces, never duplicates

        Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey(
                HousekeepingRegistrar.JOB_KEY.getName(), HousekeepingRegistrar.JOB_KEY.getGroup()));
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getCronExpression()).isEqualTo("0 15 3 * * ?");
        assertThat(((CronTrigger) trigger).getTimeZone().getID()).isEqualTo("UTC");
        assertThat(scheduler.getTriggersOfJob(HousekeepingRegistrar.JOB_KEY)).hasSize(1);
    }

    @Test
    void disablingRemovesTheJob() throws Exception {
        registrar.run(null);
        HousekeepingProperties off = new HousekeepingProperties(false, properties.cron(),
                properties.rawCheckDays(), properties.batchSize());

        new HousekeepingRegistrar(scheduler, off, txManager).run(null);

        assertThat(scheduler.checkExists(HousekeepingRegistrar.JOB_KEY)).isFalse();
        registrar.run(null); // leave it as the rest of the suite expects
    }
}
