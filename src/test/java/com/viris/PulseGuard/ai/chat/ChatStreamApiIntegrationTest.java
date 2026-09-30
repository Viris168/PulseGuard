package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.ai.InMemoryAiQuestionQuota;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sending a chat message end to end: the real endpoint, security filters and database, with a
 * fake streaming model that records every prompt it gets.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    static final List<Prompt> PROMPTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean MODEL_FAILS = new AtomicBoolean();

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
        ChatModel fakeStreamingModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    throw new UnsupportedOperationException("chat streams");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    PROMPTS.add(prompt);
                    if (MODEL_FAILS.get()) {
                        return Flux.error(new IllegalStateException("503 overloaded"));
                    }
                    return Flux.just(piece("Health is"), piece(" down."),
                            new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                                    ChatResponseMetadata.builder().model("gemini-3.1-flash-lite")
                                            .usage(new DefaultUsage(812, 41)).build()));
                }
            };
        }

        private static ChatResponse piece(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    AiConversationRepository conversations;
    @Autowired
    AiMessageRepository messages;
    @Autowired
    InMemoryAiQuestionQuota questionQuota;

    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        PROMPTS.clear();
        MODEL_FAILS.set(false);
        questionQuota.clear();
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        turnOnAskAi(alice);
    }

    @Test
    void streamsTheAnswerAndSavesBothMessages() throws Exception {
        long chat = newChat(alice);

        String stream = sendAndRead(alice, chat, "Is Health down?");

        assertThat(stream).contains("event:delta").contains("\"text\":\"Health is\"").contains("\"text\":\" down.\"")
                .contains("event:done").contains("\"status\":\"COMPLETE\"").contains("\"used\":1");
        assertThat(stream.indexOf("Health is")).isLessThan(stream.indexOf("event:done"));

        List<AiMessage> saved = messages.findAll().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
        assertThat(saved).extracting(AiMessage::getRole).containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(saved.get(0).getContent()).isEqualTo("Is Health down?");
        AiMessage answer = saved.get(1);
        assertThat(answer.getContent()).isEqualTo("Health is down.");
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(answer.getInputTokens()).isEqualTo(812);
        assertThat(answer.getOutputTokens()).isEqualTo(41);
        assertThat(answer.getModel()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(conversations.findById(chat).orElseThrow().getTitle()).isEqualTo("Is Health down?");
    }

    @Test
    void theSecondTurnSendsTheFirstAsHistory() throws Exception {
        long chat = newChat(alice);

        sendAndRead(alice, chat, "Is Health down?");
        sendAndRead(alice, chat, "Has that happened before?");

        Prompt second = PROMPTS.get(1);
        assertThat(second.getInstructions()).hasSize(4);
        assertThat(second.getInstructions().get(1).getText()).isEqualTo("Is Health down?");
        assertThat(second.getInstructions().get(2).getText()).isEqualTo("Health is down.");
        assertThat(second.getInstructions().get(3).getText()).endsWith("Question: Has that happened before?");
    }

    @Test
    void aModelFailureBeforeAnyTextIsSavedAsFailedAndNotCounted() throws Exception {
        long chat = newChat(alice);
        MODEL_FAILS.set(true);

        String stream = sendAndRead(alice, chat, "Is Health down?");

        assertThat(stream).contains("event:error").contains("wasn't counted").doesNotContain("503 overloaded");
        assertThat(messages.findAll()).extracting(AiMessage::getStatus)
                .containsExactlyInAnyOrder(MessageStatus.COMPLETE, MessageStatus.FAILED);
        assertThat(questionQuota.used(users.findByEmail("alice@example.com").orElseThrow().getId())).isZero();
    }

    @Test
    void anotherUsersChatIsNotFoundAndNothingIsSent() throws Exception {
        long chat = newChat(alice);
        turnOnAskAi(bob);

        send(bob, chat, "Is Health down?").andExpect(status().isNotFound());

        assertThat(PROMPTS).isEmpty();
        assertThat(messages.count()).isZero();
    }

    @Test
    void refusesWhenAskAiIsOff() throws Exception {
        long chat = newChat(bob); // Bob never turned Ask AI on

        send(bob, chat, "Is Health down?").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Turn on Ask AI first."));
        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void stopsAtThePlansDailyLimitWithAJsonError() throws Exception {
        long chat = newChat(alice);
        for (int i = 0; i < 5; i++) {
            sendAndRead(alice, chat, "Question " + i);
        }

        send(alice, chat, "One more?").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("all 5 questions")));
        assertThat(PROMPTS).hasSize(5);
    }

    @Test
    void refusesAChatThatIsFull() throws Exception {
        long chat = newChat(alice);
        AiConversation conversation = conversations.findById(chat).orElseThrow();
        for (int i = 0; i < ChatService.MAX_MESSAGES; i++) {
            messages.save(new AiMessage(conversation, i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "m" + i, MessageStatus.COMPLETE));
        }

        send(alice, chat, "Still there?").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Start a new chat")));
        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void validatesTheQuestion() throws Exception {
        long chat = newChat(alice);

        send(alice, chat, "  ").andExpect(status().isBadRequest());
        send(alice, chat, "x".repeat(501)).andExpect(status().isBadRequest());
        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void anApiKeyCannotSendMessages() throws Exception {
        long chat = newChat(alice);
        String json = mockMvc.perform(post("/api/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "CI"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = "Bearer " + objectMapper.readTree(json).get("secret").asString();

        send(key, chat, "Is Health down?").andExpect(status().isForbidden());
        assertThat(PROMPTS).isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions send(String auth, long chat, String question) throws Exception {
        return mockMvc.perform(post("/api/ai/conversations/{id}/messages", chat)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", question, "timeZone", "Asia/Bangkok"))));
    }

    /** Sends, waits for the stream to end, and returns everything written to it. */
    private String sendAndRead(String auth, long chat, String question) throws Exception {
        MvcResult started = send(auth, chat, question).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long newChat(String auth) throws Exception {
        String json = mockMvc.perform(post("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private void turnOnAskAi(String auth) throws Exception {
        mockMvc.perform(put("/api/ai/access")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("enabled", true, "allMonitors", true, "monitorIds", List.of()))))
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
}
