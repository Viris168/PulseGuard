package com.viris.PulseGuard.ai;

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
import com.viris.PulseGuard.enumeration.MonitorState;
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
import org.springframework.ai.chat.prompt.Prompt;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ask AI's consent, quota and privacy through the real filter chain and database, asking through
 * the chat endpoint with a fake model that records what it is sent: the prompts are what prove
 * which data reaches the provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AskAiApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    static final List<String> PROMPTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean MODEL_FAILS = new AtomicBoolean();
    static final String ANSWER = "**Shop API** is down: it returns 503 errors.";

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
        InMemoryAiQuestionQuota questionQuota() {
            return new InMemoryAiQuestionQuota();
        }

        /** Streams ANSWER, recording the newest message it was sent: the data and the question. */
        @Bean
        ChatModel fakeChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    throw new UnsupportedOperationException("Ask AI streams");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    PROMPTS.add(prompt.getInstructions().getLast().getText());
                    if (MODEL_FAILS.get()) {
                        return Flux.error(new IllegalStateException("provider unavailable"));
                    }
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER)))));
                }
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
    CheckRepository checks;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    InMemoryAiQuestionQuota questionQuota;

    private String alice;
    private String bob;
    private Monitor shop;
    private Monitor blog;
    private Monitor bobsMonitor;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors, checks, incidents and AI access follow by ON DELETE CASCADE
        PROMPTS.clear();
        MODEL_FAILS.set(false);
        questionQuota.clear();
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        User aliceUser = users.findByEmail("alice@example.com").orElseThrow();
        shop = monitor(aliceUser, "Shop API", MonitorState.DOWN);
        blog = monitor(aliceUser, "Blog", MonitorState.UP);
        bobsMonitor = monitor(users.findByEmail("bob@example.com").orElseThrow(), "Bob Payroll", MonitorState.DOWN);
        failedCheck(shop);
        openIncident(shop);
    }

    @Test
    void isOffUntilTheUserTurnsItOn() throws Exception {
        mockMvc.perform(get("/api/ai/access").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        ask(alice, "Is anything down?").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Turn on Ask AI first."));

        assertThat(PROMPTS).isEmpty();
        assertThat(questionQuota.used(userId("alice@example.com"))).isZero();
    }

    @Test
    void answersFromTheCallersOwnData() throws Exception {
        shareAll(alice);

        String stream = ask(alice, "Is anything down?").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(stream).contains("Shop API** is down").contains("event:done")
                .contains("\"used\":1").contains("\"limit\":5");

        assertThat(PROMPTS).hasSize(1);
        assertThat(PROMPTS.getFirst())
                .contains("Shop API: website/API check every 5m; status: down")
                .contains("latest error: STATUS_MISMATCH: Expected 200 but got 503")
                .contains("Shop API: ongoing since")
                .contains("Blog:")
                .endsWith("Question: Is anything down?")
                .doesNotContain("Bob Payroll")
                .doesNotContain("example.com/");
    }

    @Test
    void sendsOnlyTheMonitorsTheUserChose() throws Exception {
        saveAccess(alice, true, false, List.of(blog.getId())).andExpect(status().isOk());

        ask(alice, "How is everything?").andExpect(status().isOk());

        assertThat(PROMPTS.getFirst()).contains("Blog:").doesNotContain("Shop API")
                .contains("Monitors not shared with Ask AI: 1");
    }

    @Test
    void cannotShareAnotherUsersMonitor() throws Exception {
        saveAccess(alice, true, false, List.of(bobsMonitor.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Select at least one monitor."));

        saveAccess(alice, true, false, List.of(blog.getId(), bobsMonitor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorIds.length()").value(1))
                .andExpect(jsonPath("$.monitorIds[0]").value(blog.getId()));
    }

    @Test
    void writesTimesInTheBrowsersTimeZone() throws Exception {
        shareAll(alice);

        ask(alice, "When did it start?", "Asia/Bangkok").andExpect(status().isOk());
        ask(alice, "When did it start?", "Not/AZone").andExpect(status().isOk());

        assertThat(PROMPTS.get(0)).contains("(Asia/Bangkok)");
        assertThat(PROMPTS.get(1)).contains("(UTC)");
    }

    @Test
    void stopsAtThePlansDailyLimit() throws Exception {
        shareAll(alice);
        for (int i = 0; i < 5; i++) {
            ask(alice, "Question " + i).andExpect(status().isOk());
        }

        ask(alice, "One more?")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message", containsString("all 5 questions for today on the Free plan")));

        assertThat(PROMPTS).hasSize(5);
        mockMvc.perform(get("/api/ai/quota").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.used").value(5))
                .andExpect(jsonPath("$.limit").value(5));
    }

    @Test
    void doesNotCountAQuestionTheModelFailedToAnswer() throws Exception {
        shareAll(alice);
        MODEL_FAILS.set(true);

        String stream = ask(alice, "Is anything down?").andReturn().getResponse().getContentAsString();

        assertThat(stream).contains("event:error").contains("wasn't counted");

        assertThat(questionQuota.used(userId("alice@example.com"))).isZero();
    }

    @Test
    void validatesTheQuestion() throws Exception {
        shareAll(alice);

        ask(alice, "   ").andExpect(status().isBadRequest());
        ask(alice, "x".repeat(501)).andExpect(status().isBadRequest());

        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void eachUsersSettingsAreTheirOwn() throws Exception {
        shareAll(alice);

        mockMvc.perform(get("/api/ai/access").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(jsonPath("$.enabled").value(false));
        ask(bob, "Is anything down?").andExpect(status().isForbidden());
    }

    @Test
    void anApiKeyCannotUseAskAi() throws Exception {
        shareAll(alice);
        String json = mockMvc.perform(post("/api/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "CI"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = "Bearer " + objectMapper.readTree(json).get("secret").asString();

        send(key, newChat(alice), "Is anything down?", null).andExpect(status().isForbidden());
        saveAccess(key, false, true, List.of()).andExpect(status().isForbidden());

        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/ai/access")).andExpect(status().isUnauthorized());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions ask(String auth, String question) throws Exception {
        return ask(auth, question, null);
    }

    /**
     * Asks in a new chat, as the panel does, and waits for the answer's stream to end. Refusals
     * (403, 429, 400) come back before any stream, as plain JSON.
     */
    private ResultActions ask(String auth, String question, String timeZone) throws Exception {
        return send(auth, newChat(auth), question, timeZone);
    }

    private ResultActions send(String auth, long chat, String question, String timeZone) throws Exception {
        Map<String, Object> body = timeZone == null
                ? Map.of("question", question)
                : Map.of("question", question, "timeZone", timeZone);
        ResultActions sent = mockMvc.perform(post("/api/ai/conversations/{id}/messages", chat)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        MvcResult result = sent.andReturn();
        return result.getRequest().isAsyncStarted() ? mockMvc.perform(asyncDispatch(result)) : sent;
    }

    private long newChat(String auth) throws Exception {
        String json = mockMvc.perform(post("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private void shareAll(String auth) throws Exception {
        saveAccess(auth, true, true, List.of()).andExpect(status().isOk());
    }

    private ResultActions saveAccess(String auth, boolean enabled, boolean allMonitors, List<Long> ids) throws Exception {
        return mockMvc.perform(put("/api/ai/access")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("enabled", enabled, "allMonitors", allMonitors, "monitorIds", ids))));
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

    private Long userId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    private Monitor monitor(User owner, String name, MonitorState state) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName(name);
        monitor.setUrl("https://example.com/" + name.replace(' ', '-'));
        monitor.setState(state);
        monitor.setLastCheckedAt(Instant.now().minusSeconds(60));
        return monitors.save(monitor);
    }

    private void failedCheck(Monitor monitor) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(CheckResult.DOWN);
        check.setStatusCode(503);
        check.setResponseTimeMs(120);
        check.setErrorType(ErrorType.STATUS_MISMATCH);
        check.setErrorMessage("Expected 200 but got 503");
        check.setCheckedAt(Instant.now().minusSeconds(60));
        checks.save(check);
    }

    private void openIncident(Monitor monitor) {
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setCause("STATUS_MISMATCH: Expected 200 but got 503");
        incident.setStartedAt(Instant.now().minusSeconds(1800));
        incidents.save(incident);
    }
}
