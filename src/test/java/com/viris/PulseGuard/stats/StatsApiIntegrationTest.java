package com.viris.PulseGuard.stats;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The stats endpoint against real Postgres: FILTER, percentile_cont and bucket numbering run for real. */
@SpringBootTest
@AutoConfigureMockMvc
class StatsApiIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    CheckRepository checks;
    @Autowired
    IncidentRepository incidents;

    private String token;
    private User owner;
    private Monitor monitor;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        token = register("owner@example.com");
        owner = users.findByEmail("owner@example.com").orElseThrow();
        monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("API");
        monitor.setUrl("https://example.com/api");
        monitor = monitors.save(monitor);
        now = Instant.now();
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private void check(CheckResult result, Integer responseMs, Instant at) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(result);
        check.setResponseTimeMs(responseMs);
        check.setCheckedAt(at);
        if (result == CheckResult.DOWN) {
            check.setStatusCode(500);
            check.setErrorType(ErrorType.STATUS_MISMATCH);
        } else {
            check.setStatusCode(200);
        }
        checks.save(check);
    }

    private void setPlan(Plan plan) {
        owner.setPlan(plan);
        users.save(owner);
    }

    private ResultActions stats(String query) throws Exception {
        return mockMvc.perform(get("/api/monitors/" + monitor.getId() + "/stats" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    void cardsAreComputedExactlyInPostgres() throws Exception {
        // 11 passing checks at 100, 110, ..., 200 ms, and one fast failure (a 500 in 5 ms).
        // 59 minutes back at most, not 60: the last hourly bar starts an hour before the
        // server's own "now", truncated to the second, so a check at exactly the test's
        // now - 60m fell into the previous bar whenever the request crossed a second boundary.
        for (int i = 0; i <= 10; i++) {
            check(CheckResult.UP, 100 + i * 10, now.minus(Duration.ofMinutes(59 - i)));
        }
        check(CheckResult.DOWN, 5, now.minus(Duration.ofMinutes(30)));

        stats("?range=24h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.checksCount").value(12))
                .andExpect(jsonPath("$.uptimePct").value(closeTo(91.667, 0.001)))   // 11 of 12
                // The 5 ms failure is excluded: an outage must not look like a speed-up.
                .andExpect(jsonPath("$.avgResponseMs").value(150))
                // percentile_cont(0.95) over 100..200: position 9.5 → halfway between 190 and 200.
                .andExpect(jsonPath("$.p95ResponseMs").value(195))
                .andExpect(jsonPath("$.responseSeries.length()").value(96))
                .andExpect(jsonPath("$.uptimeBuckets.length()").value(24))
                .andExpect(jsonPath("$.responseSeries[0].avgResponseMs").isEmpty())
                .andExpect(jsonPath("$.uptimeBuckets[23].uptimePct").value(closeTo(91.667, 0.001)));
    }

    @Test
    void downtimeAndIncidentCountComeFromIncidents() throws Exception {
        check(CheckResult.DOWN, null, now.minus(Duration.ofMinutes(40)));
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setStartedAt(now.minus(Duration.ofMinutes(40)));
        incident.setResolvedAt(now.minus(Duration.ofMinutes(35)));
        incidents.save(incident);

        stats("?range=24h")
                .andExpect(jsonPath("$.incidentCount").value(1))
                .andExpect(jsonPath("$.downtimeSeconds").value(300));
    }

    @Test
    void checksOutsideTheWindowAreIgnored() throws Exception {
        check(CheckResult.DOWN, null, now.minus(Duration.ofHours(25)));
        check(CheckResult.UP, 100, now.minus(Duration.ofMinutes(5)));

        stats("?range=24h")
                .andExpect(jsonPath("$.checksCount").value(1))
                .andExpect(jsonPath("$.uptimePct").value(100.0));
    }

    @Test
    void comparesWithThePreviousPeriodWhenThePlanReachesIt() throws Exception {
        check(CheckResult.UP, 100, now.minus(Duration.ofMinutes(5)));
        check(CheckResult.DOWN, null, now.minus(Duration.ofHours(30)));   // yesterday's window

        // FREE keeps 7 days: 24h and the 24h before it both fit.
        stats("?range=24h")
                .andExpect(jsonPath("$.previous.checksCount").value(1))
                .andExpect(jsonPath("$.previous.uptimePct").value(0.0));
    }

    @Test
    void noPreviousPeriodWhenThePlanHistoryEndsInside() throws Exception {
        check(CheckResult.UP, 100, now.minus(Duration.ofHours(1)));
        check(CheckResult.UP, 100, now.minus(Duration.ofDays(10)));

        // FREE: 7d fits in 7 days of history, the 7 days before it do not.
        stats("?range=7d")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseSeries.length()").value(168))
                .andExpect(jsonPath("$.uptimeBuckets.length()").value(28))
                .andExpect(jsonPath("$.previous").isEmpty());
    }

    @Test
    void freePlanCannotRequestThirtyDays() throws Exception {
        stats("?range=30d")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("The FREE plan allows at most 7 days of history."));
    }

    @Test
    void proPlanGetsThirtyDaysWithComparison() throws Exception {
        setPlan(Plan.PRO);
        check(CheckResult.UP, 100, now.minus(Duration.ofHours(2)));
        check(CheckResult.UP, 120, now.minus(Duration.ofDays(40)));

        stats("?range=30d")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseSeries.length()").value(180))
                .andExpect(jsonPath("$.uptimeBuckets.length()").value(30))
                .andExpect(jsonPath("$.previous.avgResponseMs").value(120));
    }

    @Test
    void defaultsToTwentyFourHours() throws Exception {
        stats("").andExpect(jsonPath("$.range").value("24h"));
    }

    @Test
    void rejectsAnUnknownRange() throws Exception {
        stats("?range=1y")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.range").value("must be one of 24h, 7d, 30d"));
    }

    @Test
    void anotherTenantsMonitorIsNotFound() throws Exception {
        String bob = register("bob@example.com");

        mockMvc.perform(get("/api/monitors/" + monitor.getId() + "/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
    }
}
