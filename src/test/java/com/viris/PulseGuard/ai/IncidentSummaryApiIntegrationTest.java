package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/incidents/{id}/summary through the real filter chain, with a fake model standing in
 * for the provider: counts calls, and can be told to fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentSummaryApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** Stands in for Anthropic or Gemini; spring.ai.model.chat=none in tests, so it's the only ChatModel. */
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    static final AtomicBoolean MODEL_FAILS = new AtomicBoolean();
    static final String SUMMARY = "Shop API returned 500 errors for 20 minutes; the alert email was delivered.";

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
        ChatModel fakeChatModel() {
            return prompt -> {
                MODEL_CALLS.incrementAndGet();
                if (MODEL_FAILS.get()) {
                    throw new IllegalStateException("provider unavailable");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(SUMMARY))));
            };
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
    IncidentRepository incidents;

    private String alice;
    private String bob;
    private Incident aliceIncident;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors and incidents follow by ON DELETE CASCADE
        MODEL_CALLS.set(0);
        MODEL_FAILS.set(false);
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        aliceIncident = resolvedIncident(monitor(users.findByEmail("alice@example.com").orElseThrow()));
    }

    @Test
    void returnsASummaryOfTheCallersIncident() throws Exception {
        mockMvc.perform(summary(aliceIncident.getId()).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(SUMMARY))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());

        Incident stored = incidents.findById(aliceIncident.getId()).orElseThrow();
        assertThat(stored.getAiSummary()).isEqualTo(SUMMARY);
        assertThat(stored.getAiSummaryAt()).isNotNull();
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void servesRepeatViewsFromTheStoredSummary() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(summary(aliceIncident.getId()).header(HttpHeaders.AUTHORIZATION, alice))
                    .andExpect(status().isOk());
        }

        assertThat(MODEL_CALLS).hasValue(1);
    }

    @Test
    void anotherUsersIncidentIsNotFoundAndNeverReachesTheModel() throws Exception {
        mockMvc.perform(summary(aliceIncident.getId()).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());

        assertThat(MODEL_CALLS).hasValue(0);
        assertThat(incidents.findById(aliceIncident.getId()).orElseThrow().getAiSummary()).isNull();
    }

    @Test
    void answersNoContentWhenTheProviderFails() throws Exception {
        MODEL_FAILS.set(true);

        mockMvc.perform(summary(aliceIncident.getId()).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(incidents.findById(aliceIncident.getId()).orElseThrow().getAiSummary()).isNull();
    }

    @Test
    void anApiKeyCannotRequestSummaries() throws Exception {
        String json = mockMvc.perform(post("/api/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "CI"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = "Bearer " + objectMapper.readTree(json).get("secret").asString();

        mockMvc.perform(summary(aliceIncident.getId()).header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isForbidden());

        assertThat(MODEL_CALLS).hasValue(0);
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(summary(aliceIncident.getId()))
                .andExpect(status().isUnauthorized());

        assertThat(MODEL_CALLS).hasValue(0);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summary(Long id) {
        return get("/api/incidents/{id}/summary", id);
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

    private Monitor monitor(User owner) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("Shop API");
        monitor.setUrl("https://example.com/health");
        return monitors.save(monitor);
    }

    private Incident resolvedIncident(Monitor monitor) {
        Instant started = Instant.parse("2026-09-27T10:00:00Z");
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setCause("HTTP 500: got 500");
        incident.setStartedAt(started);
        incident.setResolvedAt(started.plusSeconds(1200));
        return incidents.save(incident);
    }
}
