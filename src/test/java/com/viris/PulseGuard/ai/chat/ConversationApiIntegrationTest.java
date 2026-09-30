package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The conversation endpoints through the real filter chain and database, with two users. */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationApiIntegrationTest {

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

    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // conversations, messages and feedback follow by ON DELETE CASCADE
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
    }

    @Test
    void startsAnEmptyChat() throws Exception {
        create(alice).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("New chat"));
    }

    @Test
    void listsOnlyTheCallersChatsMostRecentlyUsedFirst() throws Exception {
        long older = id(create(alice));
        long newer = id(create(alice));
        id(create(bob));
        touch(older, Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[1].id").value(older));
    }

    @Test
    void readsMessagesOldestFirstWithTheCallersRatings() throws Exception {
        long chat = id(create(alice));
        long question = message(chat, MessageRole.USER, "Is Health down?", MessageStatus.COMPLETE);
        long answer = message(chat, MessageRole.ASSISTANT, "Yes, it returns 503.", MessageStatus.COMPLETE);
        rate(alice, answer, 1).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", chat).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(question))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].rating").doesNotExist())
                .andExpect(jsonPath("$[1].content").value("Yes, it returns 503."))
                .andExpect(jsonPath("$[1].status").value("COMPLETE"))
                .andExpect(jsonPath("$[1].rating").value(1));
    }

    @Test
    void renamesAChat() throws Exception {
        long chat = id(create(alice));

        rename(alice, chat, "  Health outage  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Health outage"));
        rename(alice, chat, "   ").andExpect(status().isBadRequest());
        rename(alice, chat, "x".repeat(101)).andExpect(status().isBadRequest());

        assertThat(conversations.findById(chat).orElseThrow().getTitle()).isEqualTo("Health outage");
    }

    @Test
    void deletesAChatWithItsMessages() throws Exception {
        long chat = id(create(alice));
        long answer = message(chat, MessageRole.ASSISTANT, "Hi", MessageStatus.COMPLETE);

        mockMvc.perform(delete("/api/ai/conversations/{id}", chat).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());

        assertThat(conversations.findById(chat)).isEmpty();
        assertThat(messages.findById(answer)).isEmpty();
    }

    @Test
    void anotherUsersChatIsNotFoundEverywhere() throws Exception {
        long chat = id(create(alice));
        long answer = message(chat, MessageRole.ASSISTANT, "Secret answer", MessageStatus.COMPLETE);

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", chat).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        rename(bob, chat, "Mine now").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/ai/conversations/{id}", chat).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        rate(bob, answer, -1).andExpect(status().isNotFound());

        assertThat(conversations.findById(chat).orElseThrow().getTitle()).isEqualTo("New chat");
    }

    @Test
    void ratingAgainReplacesTheEarlierRating() throws Exception {
        long chat = id(create(alice));
        long answer = message(chat, MessageRole.ASSISTANT, "Answer", MessageStatus.COMPLETE);

        rate(alice, answer, 1).andExpect(status().isNoContent());
        rate(alice, answer, -1).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", chat).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$[0].rating").value(-1));
    }

    @Test
    void ratesOnlyAnswersWithThumbsUpOrDown() throws Exception {
        long chat = id(create(alice));
        long question = message(chat, MessageRole.USER, "Question", MessageStatus.COMPLETE);
        long answer = message(chat, MessageRole.ASSISTANT, "Answer", MessageStatus.COMPLETE);

        rate(alice, question, 1).andExpect(status().isBadRequest());
        rate(alice, answer, 5).andExpect(status().isBadRequest());
        rate(alice, 999_999L, 1).andExpect(status().isNotFound());
    }

    @Test
    void anApiKeyCannotReadChats() throws Exception {
        String json = mockMvc.perform(post("/api/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "CI"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = "Bearer " + objectMapper.readTree(json).get("secret").asString();

        mockMvc.perform(get("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isForbidden());
        create(key).andExpect(status().isForbidden());
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/ai/conversations")).andExpect(status().isUnauthorized());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions create(String auth) throws Exception {
        return mockMvc.perform(post("/api/ai/conversations").header(HttpHeaders.AUTHORIZATION, auth));
    }

    private long id(ResultActions created) throws Exception {
        return objectMapper.readTree(created.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions rename(String auth, long chat, String title) throws Exception {
        return mockMvc.perform(patch("/api/ai/conversations/{id}", chat)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", title))));
    }

    private ResultActions rate(String auth, long messageId, int rating) throws Exception {
        return mockMvc.perform(put("/api/ai/messages/{id}/feedback", messageId)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rating", rating))));
    }

    /** Messages are written by ChatService once streaming exists; here they're stored directly. */
    private long message(long chat, MessageRole role, String content, MessageStatus status) {
        AiConversation conversation = conversations.findById(chat).orElseThrow();
        return messages.save(new AiMessage(conversation, role, content, status)).getId();
    }

    private void touch(long chat, Instant updatedAt) {
        AiConversation conversation = conversations.findById(chat).orElseThrow();
        conversation.setUpdatedAt(updatedAt);
        conversations.save(conversation);
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
