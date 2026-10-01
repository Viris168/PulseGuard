package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.common.TestAccounts;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.billing.PlanChangeService;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Heartbeats end to end: created through the API, pinged with no login, swept when silent,
 * alerted on, resolved by the next ping. Real Postgres and filter chain; SMTP is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HeartbeatApiIntegrationTest {

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

        @Bean
        @Primary
        SafeUrlValidator urlValidator() {
            return new SafeUrlValidator(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        }
    }

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    com.viris.PulseGuard.check.CheckRepository checks;
    @Autowired
    SchedulerService schedulerService;
    @Autowired
    HeartbeatSweeper sweeper;
    @Autowired
    PlanChangeService planChangeService;

    private String alice;
    private String bob;

    private static final String HEARTBEAT = """
            {"type":"HEARTBEAT","name":"Nightly backup","url":"","method":"GET","expectedStatus":200,
             "intervalSeconds":3600,"timeoutMs":0,"graceSeconds":600}
            """;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors, pings, checks and incidents follow by ON DELETE CASCADE
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        TestAccounts.awaitVerificationEmail(mailSender, email);
        TestAccounts.markVerified(users, email);
        return objectMapper.readTree(body).get("token").asString();
    }

    private JsonNode createHeartbeat(String auth) throws Exception {
        String json = mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(HEARTBEAT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static String pingPath(JsonNode monitor) {
        String url = monitor.get("pingUrl").asString();
        return url.substring(url.indexOf("/api/ping/"));
    }

    private ResultActions ping(JsonNode monitor) throws Exception {
        return mockMvc.perform(post(pingPath(monitor)).with(r -> {
            r.setRemoteAddr("203.0.113.7");
            return r;
        }));
    }

    private Monitor reload(JsonNode monitor) {
        return monitors.findById(monitor.get("id").asLong()).orElseThrow();
    }

    /** Moves the deadline into the past, as if the period plus grace had gone by in silence. */
    private Instant expireDeadline(JsonNode monitor) {
        Monitor m = reload(monitor);
        // Microseconds: what Postgres stores, so the value read back compares equal.
        Instant deadline = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);
        m.setPingDeadline(deadline);
        monitors.saveAndFlush(m);
        return deadline;
    }

    @Test
    void createsAHeartbeatWithAPingUrlAndNoCheckJob() throws Exception {
        JsonNode created = createHeartbeat(alice);

        assertThat(created.get("type").asString()).isEqualTo("HEARTBEAT");
        assertThat(created.get("graceSeconds").asInt()).isEqualTo(600);
        assertThat(created.get("pingUrl").asString()).matches("https://ping\\.test/api/ping/[A-Za-z0-9]{24}");
        assertThat(created.get("lastCheckedAt").isNull()).isTrue();
        assertThat(schedulerService.isScheduled(created.get("id").asLong())).isFalse();

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$[0].type").value("HEARTBEAT"))
                .andExpect(jsonPath("$[0].pingUrl").value(created.get("pingUrl").asString()));
    }

    @Test
    void aHeartbeatNeedsAGracePeriodButNoUrl() throws Exception {
        mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HEARTBEAT","name":"Backup","intervalSeconds":3600}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.graceSeconds").value("Grace period is required"))
                .andExpect(jsonPath("$.fieldErrors.url").doesNotExist());
    }

    @Test
    void theFreePlansPollingMinimumDoesNotApplyToHeartbeats() throws Exception {
        mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HEARTBEAT","name":"Queue worker","intervalSeconds":60,"graceSeconds":30}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void aPingNeedsNoLoginAndStartsTheClock() throws Exception {
        JsonNode created = createHeartbeat(alice);

        ping(created).andExpect(status().isOk()).andExpect(content().string("OK\n"));
        // GET and HEAD work too; these come within 10s of the first, so they are answered, not stored.
        mockMvc.perform(get(pingPath(created))).andExpect(status().isOk()).andExpect(content().string("OK\n"));
        mockMvc.perform(head(pingPath(created))).andExpect(status().isOk());

        Monitor m = reload(created);
        assertThat(m.getLastCheckedAt()).isNotNull();
        assertThat(m.getPingDeadline()).isEqualTo(m.getLastCheckedAt().plusSeconds(3600 + 600));
        mockMvc.perform(get("/api/monitors/" + m.getId() + "/pings").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceIp").value("203.0.113.7"));
    }

    @Test
    void anUnknownPingUrlIsNotFound() throws Exception {
        mockMvc.perform(get("/api/ping/" + HeartbeatSchedule.newToken())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/ping/not-a-token")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aMissedDeadlineOpensAnIncidentAndAlertsAndTheNextPingResolvesIt() throws Exception {
        JsonNode created = createHeartbeat(alice);
        long id = created.get("id").asLong();
        ping(created).andExpect(status().isOk());
        Instant deadline = expireDeadline(created);

        assertThat(sweeper.sweep(Instant.now())).isEqualTo(1);

        Monitor down = reload(created);
        assertThat(down.getState()).isEqualTo(MonitorState.DOWN);
        assertThat(down.getPingDeadline()).isAfter(Instant.now());
        Incident open = incidents.findByMonitorIdAndStatus(id, IncidentStatus.OPEN).orElseThrow();
        assertThat(open.getCause()).isEqualTo("No ping received within 1h (+10m grace)");
        assertThat(open.getStartedAt()).isEqualTo(deadline);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("🔴 DOWN: Nightly backup");
        assertThat(mail.getValue().getText()).contains("missed its check-in", "every 1h (+10m grace)");

        mockMvc.perform(get("/api/incidents/" + open.getId()).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.monitorType").value("HEARTBEAT"))
                .andExpect(jsonPath("$.timeline[?(@.type == 'OPENED')]").exists());

        // A second sweep while still silent opens nothing new.
        sweeper.sweep(Instant.now());
        assertThat(incidents.findAllByMonitorIdOrderByStartedAtDesc(id)).hasSize(1);

        ping(created).andExpect(status().isOk());

        assertThat(reload(created).getState()).isEqualTo(MonitorState.UP);
        Incident resolved = incidents.findById(open.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
    }

    @Test
    void theSweepLeavesNeverPingedAndPausedHeartbeatsAlone() throws Exception {
        createHeartbeat(alice); // never pinged: no deadline yet
        JsonNode paused = createHeartbeat(alice);
        ping(paused).andExpect(status().isOk());
        expireDeadline(paused);
        mockMvc.perform(post("/api/monitors/" + paused.get("id").asLong() + "/pause")
                        .header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk());

        assertThat(sweeper.sweep(Instant.now())).isZero();
        assertThat(reload(paused).getState()).isEqualTo(MonitorState.UP);
    }

    @Test
    void aPausedHeartbeatAnswersPingsButRecordsNothingAndResumeRestartsTheClock() throws Exception {
        JsonNode created = createHeartbeat(alice);
        long id = created.get("id").asLong();
        ping(created).andExpect(status().isOk());
        expireDeadline(created);
        mockMvc.perform(post("/api/monitors/" + id + "/pause").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk());

        ping(created).andExpect(status().isOk()).andExpect(content().string("OK (monitor paused, ping ignored)\n"));
        mockMvc.perform(get("/api/monitors/" + id + "/pings").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/monitors/" + id + "/resume").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk());
        // A full period from now, not "missed" for the time it was paused.
        assertThat(reload(created).getPingDeadline()).isAfter(Instant.now().plusSeconds(3600));
        assertThat(sweeper.sweep(Instant.now())).isZero();
    }

    @Test
    void theTestPingIsARealPingForTheOwnerOnly() throws Exception {
        JsonNode created = createHeartbeat(alice);
        long id = created.get("id").asLong();

        mockMvc.perform(post("/api/monitors/" + id + "/test-ping").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheckedAt").isNotEmpty());
        mockMvc.perform(post("/api/monitors/" + id + "/test-ping").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/monitors/" + id + "/pings").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/monitors/" + id + "/pause").header(HttpHeaders.AUTHORIZATION, alice));
        mockMvc.perform(post("/api/monitors/" + id + "/test-ping").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isConflict());
    }

    @Test
    void anHttpMonitorHasNoPingsAndATypeCannotChange() throws Exception {
        String httpJson = mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","url":"https://example.com","method":"GET","expectedStatus":200,
                                 "intervalSeconds":300,"timeoutMs":5000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.pingUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long httpId = objectMapper.readTree(httpJson).get("id").asLong();

        mockMvc.perform(post("/api/monitors/" + httpId + "/test-ping").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/monitors/" + httpId).header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content(HEARTBEAT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists());
    }

    @Test
    void aDowngradeDoesNotSlowHeartbeatsDown() throws Exception {
        User user = users.findByEmail("alice@example.com").orElseThrow();
        user.setPlan(Plan.PRO);
        users.save(user);
        mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HEARTBEAT","name":"Queue worker","intervalSeconds":60,"graceSeconds":30}
                                """))
                .andExpect(status().isCreated());

        planChangeService.applyPlan(user.getId(), Plan.FREE);

        List<Monitor> mine = monitors.findAllByUserId(user.getId());
        assertThat(mine).singleElement().extracting(Monitor::getIntervalSeconds).isEqualTo(60);
    }

    /** Moves the last ping back in time, as if the job had pinged that long ago. */
    private void lastPingedAgo(JsonNode monitor, Duration ago) {
        Monitor m = reload(monitor);
        m.setLastCheckedAt(Instant.now().minus(ago));
        monitors.saveAndFlush(m);
    }

    @Test
    void aJobPingingFarMoreOftenThanItsPeriodDoesNotInflateUptime() throws Exception {
        JsonNode created = createHeartbeat(alice); // expects a ping every hour
        long id = created.get("id").asLong();
        ping(created).andExpect(status().isOk());
        lastPingedAgo(created, Duration.ofMinutes(1));
        ping(created).andExpect(status().isOk());
        lastPingedAgo(created, Duration.ofMinutes(1));
        ping(created).andExpect(status().isOk());

        // Every ping is in the log, but only the first counts as a check for uptime.
        mockMvc.perform(get("/api/monitors/" + id + "/pings").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(checks.findByMonitorIdOrderByCheckedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(1);

        lastPingedAgo(created, Duration.ofMinutes(31)); // over half the period
        ping(created).andExpect(status().isOk());
        assertThat(checks.findByMonitorIdOrderByCheckedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(2);
    }

    @Test
    void thePingThatEndsAnOutageIsNeverSkipped() throws Exception {
        JsonNode created = createHeartbeat(alice);
        ping(created).andExpect(status().isOk());
        expireDeadline(created);
        sweeper.sweep(Instant.now());
        assertThat(reload(created).getState()).isEqualTo(MonitorState.DOWN);

        ping(created).andExpect(status().isOk()); // seconds after the last one, but it ends the outage

        assertThat(reload(created).getState()).isEqualTo(MonitorState.UP);
    }
}
