package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.scheduling.SchedulerService;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises monitor CRUD through the real filter chain, with two tenants, so authorization
 * is proven where it actually runs rather than only in the service unit tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonitorApiIntegrationTest {

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

        /** Stubbed DNS: ".internal" hosts look private, everything else public. No network. */
        @Bean
        @Primary
        SafeUrlValidator urlValidator() {
            return new SafeUrlValidator(host -> new InetAddress[]{
                    InetAddress.getByName(host.endsWith(".internal") ? "10.0.0.1" : "93.184.216.34")
            });
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SchedulerService schedulerService;

    private String aliceToken;
    private String bobToken;

    private static final String VALID_BODY = """
            {"name":"API health","url":"https://example.com/health","method":"GET",
             "expectedStatus":200,"intervalSeconds":60,"timeoutMs":5000}
            """;

    @BeforeEach
    void registerTwoTenants() throws Exception {
        monitors.deleteAll();
        users.deleteAll();
        aliceToken = registerPro("alice@example.com");
        bobToken = registerPro("bob@example.com");
    }

    /** Registers a user and upgrades them to PRO, so the 60s interval here is allowed. */
    private String registerPro(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        User user = users.findByEmail(email).orElseThrow();
        user.setPlan(Plan.PRO);
        users.save(user);

        return objectMapper.readTree(body).get("token").asString();
    }

    private String createMonitor(String token) throws Exception {
        return mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static long idOf(String json, ObjectMapper mapper) {
        JsonNode node = mapper.readTree(json);
        return node.get("id").asLong();
    }

    @Test
    void createsAMonitorAndReturnsItsLocation() throws Exception {
        mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("API health"))
                .andExpect(jsonPath("$.state").value("UP"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/monitors")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsOnlyTheCallersMonitors() throws Exception {
        createMonitor(aliceToken);
        createMonitor(bobToken);

        mockMvc.perform(get("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anotherTenantsMonitorIsNotFound() throws Exception {
        long aliceMonitor = idOf(createMonitor(aliceToken), objectMapper);
        String bob = "Bearer " + bobToken;

        mockMvc.perform(get("/api/monitors/" + aliceMonitor).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/monitors/" + aliceMonitor).header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/monitors/" + aliceMonitor).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());

        // Alice's monitor, and its schedule, survived every attempt.
        assertThat(monitors.findById(aliceMonitor)).isPresent();
        assertThat(schedulerService.isScheduled(aliceMonitor)).isTrue();
    }

    @Test
    void updatesAndFetchesBack() throws Exception {
        long id = idOf(createMonitor(aliceToken), objectMapper);

        mockMvc.perform(put("/api/monitors/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed","url":"https://example.com/health","method":"HEAD",
                                 "expectedStatus":204,"intervalSeconds":120,"timeoutMs":9000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));

        mockMvc.perform(get("/api/monitors/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(jsonPath("$.method").value("HEAD"))
                .andExpect(jsonPath("$.expectedStatus").value(204))
                .andExpect(jsonPath("$.intervalSeconds").value(120));
    }

    @Test
    void pausesAndResumes() throws Exception {
        long id = idOf(createMonitor(aliceToken), objectMapper);
        String alice = "Bearer " + aliceToken;

        assertThat(schedulerService.isScheduled(id)).as("scheduled on create").isTrue();

        mockMvc.perform(post("/api/monitors/" + id + "/pause").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.isActive").value(false));
        assertThat(schedulerService.isScheduled(id)).as("unscheduled on pause").isFalse();

        mockMvc.perform(post("/api/monitors/" + id + "/resume").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.isActive").value(true));
        assertThat(schedulerService.isScheduled(id)).as("scheduled again on resume").isTrue();
    }

    @Test
    void deleteRemovesTheMonitor() throws Exception {
        long id = idOf(createMonitor(aliceToken), objectMapper);

        mockMvc.perform(delete("/api/monitors/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(monitors.findById(id)).isEmpty();
        assertThat(schedulerService.isScheduled(id)).as("unscheduled on delete").isFalse();
    }

    @Test
    void rejectsAnInternalUrlWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"internal","url":"http://admin.internal/health","method":"GET",
                                 "expectedStatus":200,"intervalSeconds":60,"timeoutMs":5000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rejected")));
    }

    @Test
    void rejectsInvalidBodyWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","url":"ftp://example.com","method":"TRACE",
                                 "expectedStatus":200,"intervalSeconds":10,"timeoutMs":5000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.url").exists())
                .andExpect(jsonPath("$.fieldErrors.method").exists());
    }

    @Test
    void enforcesThePlanMonitorCap() throws Exception {
        // Drop Alice to FREE (3 monitors, 300s minimum) and fill the allowance.
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        alice.setPlan(Plan.FREE);
        users.save(alice);

        String freeBody = """
                {"name":"API health","url":"https://example.com/health","method":"GET",
                 "expectedStatus":200,"intervalSeconds":300,"timeoutMs":5000}
                """;
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/monitors")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON).content(freeBody))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(freeBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at most 3 monitors")));
    }

    @Test
    void enforcesThePlanMinimumInterval() throws Exception {
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        alice.setPlan(Plan.FREE);
        users.save(alice);

        // 60s passes DTO validation but FREE requires 300s.
        mockMvc.perform(post("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 300")));
    }
}
