package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.ai.InMemoryAiQuestionQuota;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chats started from a monitor or incident page: who may start them, and what the model is told. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatContextApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");
    static final List<Prompt> PROMPTS = new CopyOnWriteArrayList<>();

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

        @Bean
        ChatModel fakeModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    throw new UnsupportedOperationException("chat streams");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    PROMPTS.add(prompt);
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Noted.")))));
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
    IncidentRepository incidents;
    @Autowired
    AiConversationRepository conversations;
    @Autowired
    InMemoryAiQuestionQuota questionQuota;

    private String alice;
    private String bob;
    private Monitor health;
    private Monitor blog;
    private Incident outage;
    private Monitor bobsMonitor;
    private Incident bobsIncident;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        PROMPTS.clear();
        questionQuota.clear();
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        User aliceUser = users.findByEmail("alice@example.com").orElseThrow();
        health = monitor(aliceUser, "Health");
        blog = monitor(aliceUser, "Blog");
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        outage = incident(health, yesterday.atTime(14, 36).atZone(ZONE).toInstant(),
                yesterday.atTime(15, 6).atZone(ZONE).toInstant());
        bobsMonitor = monitor(users.findByEmail("bob@example.com").orElseThrow(), "Payroll");
        bobsIncident = incident(bobsMonitor, Instant.now().minusSeconds(3600), null);
        share(alice, List.of(health.getId())); // Blog is not shared
    }

    @Test
    void startsAChatAboutAMonitor() throws Exception {
        create(alice, Map.of("monitorId", health.getId())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextMonitorId").value(health.getId()))
                .andExpect(jsonPath("$.contextIncidentId").doesNotExist());
    }

    @Test
    void aChatAboutAnIncidentAlsoRemembersItsMonitor() throws Exception {
        create(alice, Map.of("incidentId", outage.getId())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextIncidentId").value(outage.getId()))
                .andExpect(jsonPath("$.contextMonitorId").value(health.getId()));
    }

    @Test
    void aPlainChatStillNeedsNoBody() throws Exception {
        mockMvc.perform(post("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextMonitorId").doesNotExist());
    }

    @Test
    void anotherUsersMonitorOrIncidentIsNotFound() throws Exception {
        create(alice, Map.of("monitorId", bobsMonitor.getId())).andExpect(status().isNotFound());
        create(alice, Map.of("incidentId", bobsIncident.getId())).andExpect(status().isNotFound());
        assertThat(conversations.count()).isZero();
    }

    @Test
    void aMonitorNotSharedWithAskAiIsNotFound() throws Exception {
        create(alice, Map.of("monitorId", blog.getId())).andExpect(status().isNotFound());
    }

    @Test
    void refusesWhenAskAiIsOff() throws Exception {
        create(bob, Map.of("monitorId", bobsMonitor.getId())).andExpect(status().isForbidden());
    }

    @Test
    void refusesBothAMonitorAndAnIncident() throws Exception {
        create(alice, Map.of("monitorId", health.getId(), "incidentId", outage.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyTurnTellsTheModelWhichIncidentThisIsAbout() throws Exception {
        long chat = id(create(alice, Map.of("incidentId", outage.getId())));

        send(chat, "Why did this happen?");
        send(chat, "Has it happened before?");

        for (Prompt prompt : PROMPTS) {
            assertThat(prompt.getInstructions().getLast().getText())
                    .contains("Page: The user started this chat from the page of the incident on Health that started ")
                    .contains("14:36, resolved ").contains("(30m)");
        }
        assertThat(PROMPTS).hasSize(2);
    }

    @Test
    void stopsMentioningAMonitorThatIsNoLongerShared() throws Exception {
        long chat = id(create(alice, Map.of("monitorId", health.getId())));
        send(chat, "How is it?");

        share(alice, List.of(blog.getId()));
        send(chat, "And now?");

        assertThat(PROMPTS.get(0).getInstructions().getLast().getText()).contains("page of the monitor Health");
        assertThat(PROMPTS.get(1).getInstructions().getLast().getText()).doesNotContain("Page:");
    }

    @Test
    void deletingTheMonitorKeepsTheChatAndDropsTheContext() throws Exception {
        long chat = id(create(alice, Map.of("monitorId", health.getId())));

        monitors.deleteById(health.getId());

        assertThat(conversations.findById(chat).orElseThrow().getContextMonitorId()).isNull();
        mockMvc.perform(get("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$[0].id").value(chat));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions create(String auth, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/ai/conversations")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long id(ResultActions created) throws Exception {
        return objectMapper.readTree(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private void send(long chat, String question) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/ai/conversations/{id}/messages", chat)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", question, "timeZone", ZONE.getId()))))
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    private void share(String auth, List<Long> monitorIds) throws Exception {
        mockMvc.perform(put("/api/ai/access")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("enabled", true, "allMonitors", false, "monitorIds", monitorIds))))
                .andExpect(status().isOk());
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

    private Incident incident(Monitor monitor, Instant started, Instant resolved) {
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(resolved == null ? IncidentStatus.OPEN : IncidentStatus.RESOLVED);
        incident.setStartedAt(started);
        incident.setResolvedAt(resolved);
        incident.setCause("STATUS_MISMATCH: Expected 200 but got 503");
        return incidents.save(incident);
    }
}
