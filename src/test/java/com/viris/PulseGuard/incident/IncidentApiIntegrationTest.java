package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.notification.Notification;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Incident list through the real filter chain, two tenants, and a guard against N+1 queries. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class IncidentApiIntegrationTest {

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

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

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
    IncidentQueryService incidentQueryService;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    CheckRepository checks;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    NotificationRepository notifications;

    private String aliceToken;
    private User alice;
    private Monitor api;
    private Monitor web;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors and incidents follow by ON DELETE CASCADE
        aliceToken = register("alice@example.com");
        alice = users.findByEmail("alice@example.com").orElseThrow();
        api = monitor(alice, "API");
        web = monitor(alice, "Web");
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private Monitor monitor(User owner, String name) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName(name);
        monitor.setUrl("https://example.com/" + name);
        return monitors.save(monitor);
    }

    private Incident incident(Monitor monitor, IncidentStatus status, Instant startedAt) {
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(status);
        incident.setCause("TIMEOUT: No response");
        incident.setStartedAt(startedAt);
        if (status == IncidentStatus.RESOLVED) {
            incident.setResolvedAt(startedAt.plusSeconds(300));
        }
        return incidents.save(incident);
    }

    private ResultActions listAsAlice(String query) throws Exception {
        return mockMvc.perform(get("/api/incidents" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken));
    }

    @Test
    void listsOnlyTheCallersIncidentsNewestFirstInTheFrontendShape() throws Exception {
        incident(api, IncidentStatus.RESOLVED, T0);
        Incident newest = incident(web, IncidentStatus.OPEN, T0.plusSeconds(3600));
        register("bob@example.com");
        User bob = users.findByEmail("bob@example.com").orElseThrow();
        incident(monitor(bob, "Bob's"), IncidentStatus.OPEN, T0.plusSeconds(7200));

        listAsAlice("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newest.getId()))
                .andExpect(jsonPath("$[0].monitorId").value(web.getId()))
                .andExpect(jsonPath("$[0].monitorName").value("Web"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].cause").value("TIMEOUT: No response"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-09-27T11:00:00Z"))
                .andExpect(jsonPath("$[0].resolvedAt").isEmpty())
                .andExpect(jsonPath("$[1].status").value("RESOLVED"));
    }

    @Test
    void filtersByStatusAndMonitor() throws Exception {
        incident(api, IncidentStatus.RESOLVED, T0);
        incident(api, IncidentStatus.OPEN, T0.plusSeconds(600));
        incident(web, IncidentStatus.RESOLVED, T0.plusSeconds(1200));

        listAsAlice("?status=OPEN").andExpect(jsonPath("$.length()").value(1));
        listAsAlice("?monitorId=" + api.getId()).andExpect(jsonPath("$.length()").value(2));
        listAsAlice("?status=RESOLVED&monitorId=" + api.getId()).andExpect(jsonPath("$.length()").value(1));
        listAsAlice("").andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void anotherTenantsMonitorIdFilterReturnsNothing() throws Exception {
        register("bob@example.com");
        User bob = users.findByEmail("bob@example.com").orElseThrow();
        Monitor bobs = monitor(bob, "Bob's");
        incident(bobs, IncidentStatus.OPEN, T0);

        // A filter can only narrow the caller's own incidents, never reach someone else's.
        listAsAlice("?monitorId=" + bobs.getId()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listLoadsMonitorsInOneQuery() {
        for (int i = 0; i < 5; i++) {
            incident(i % 2 == 0 ? api : web, IncidentStatus.RESOLVED, T0.plusSeconds(i * 600L));
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        var list = incidentQueryService.list(alice.getId(), null, null, 50);

        assertThat(list).hasSize(5).extracting(r -> r.monitorName()).containsOnly("API", "Web");
        // join fetch: incidents and their monitors in a single SELECT, not 1 + 5.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void rejectsAnUnknownStatusWithBadRequest() throws Exception {
        listAsAlice("?status=BOGUS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.status").value("must be one of OPEN, RESOLVED"));
    }

    @Test
    void rejectsOversizedLimit() throws Exception {
        listAsAlice("?limit=500")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.limit").exists());
    }

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
    }

    // --- detail + timeline ------------------------------------------------

    private void recordCheck(Monitor monitor, CheckResult result, Instant at) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(result);
        check.setCheckedAt(at);
        if (result == CheckResult.DOWN) {
            check.setErrorType(ErrorType.STATUS_MISMATCH);
            check.setErrorMessage("Expected 200 but got 500");
        }
        checks.save(check);
    }

    private void recordAlert(Incident incident, ChannelType type, String target, NotificationEventType event, Instant at) {
        NotificationChannel channel = new NotificationChannel();
        channel.setUser(alice);
        channel.setType(type);
        channel.setTarget(target);
        channels.save(channel);
        Notification notification = new Notification();
        notification.setIncident(incident);
        notification.setChannel(channel);
        notification.setEventType(event);
        notification.setStatus(NotificationStatus.SENT);
        notification.setSentAt(at);
        notifications.save(notification);
    }

    @Test
    void detailTellsTheIncidentStoryInTheFrontendShape() throws Exception {
        for (int i = 0; i < 3; i++) {
            recordCheck(api, CheckResult.DOWN, T0.plusSeconds(60L * i));
        }
        recordCheck(api, CheckResult.UP, T0.plusSeconds(180));
        recordCheck(api, CheckResult.UP, T0.plusSeconds(240));
        Incident incident = incident(api, IncidentStatus.RESOLVED, T0);
        incident.setResolvedAt(T0.plusSeconds(240));
        incidents.save(incident);
        recordAlert(incident, ChannelType.EMAIL, "alice@example.com", NotificationEventType.OPENED, T0.plusSeconds(121));
        recordAlert(incident, ChannelType.SLACK, "https://hooks.slack.com/services/T0/B0/SECRET",
                NotificationEventType.RESOLVED, T0.plusSeconds(241));

        mockMvc.perform(get("/api/incidents/" + incident.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorName").value("API"))
                .andExpect(jsonPath("$.monitorType").value("HTTP"))
                .andExpect(jsonPath("$.monitorUrl").value("https://example.com/API"))
                .andExpect(jsonPath("$.monitorMethod").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(300))
                .andExpect(jsonPath("$.timeline.length()").value(9))
                .andExpect(jsonPath("$.timeline[0].type").value("CHECK_FAILED"))
                .andExpect(jsonPath("$.timeline[0].detail").value("STATUS_MISMATCH: Expected 200 but got 500"))
                .andExpect(jsonPath("$.timeline[3].type").value("OPENED"))
                .andExpect(jsonPath("$.timeline[3].failedChecks").value(3))
                .andExpect(jsonPath("$.timeline[4].type").value("NOTIFIED"))
                .andExpect(jsonPath("$.timeline[4].event").value("OPENED"))
                .andExpect(jsonPath("$.timeline[4].channel").value("EMAIL"))
                .andExpect(jsonPath("$.timeline[4].target").value("alice@example.com"))
                .andExpect(jsonPath("$.timeline[4].status").value("SENT"))
                .andExpect(jsonPath("$.timeline[5].type").value("CHECK_PASSED"))
                .andExpect(jsonPath("$.timeline[7].type").value("RESOLVED"))
                .andExpect(jsonPath("$.timeline[7].passedChecks").value(2))
                .andExpect(jsonPath("$.timeline[8].type").value("NOTIFIED"))
                // The Slack URL is the credential itself: only its host may reach the browser.
                .andExpect(jsonPath("$.timeline[8].target").value("https://hooks.slack.com/••••"));
    }

    @Test
    void anotherTenantsIncidentIsNotFound() throws Exception {
        register("bob@example.com");
        User bob = users.findByEmail("bob@example.com").orElseThrow();
        Incident bobs = incident(monitor(bob, "Bob's"), IncidentStatus.OPEN, T0);

        mockMvc.perform(get("/api/incidents/" + bobs.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unknownIncidentIsNotFound() throws Exception {
        mockMvc.perform(get("/api/incidents/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }
}
