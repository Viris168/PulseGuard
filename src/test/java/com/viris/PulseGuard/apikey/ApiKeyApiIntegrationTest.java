package com.viris.PulseGuard.apikey;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keys end to end: managed with a session, used as a bearer token, refused on account-security
 * routes, and dead the moment they are revoked. Two tenants, real filter chain, real Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyApiIntegrationTest {

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
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    ApiKeyRepository apiKeys;
    @Autowired
    ObjectMapper objectMapper;

    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // keys and monitors follow by ON DELETE CASCADE
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        Monitor m = new Monitor();
        m.setUser(users.findByEmail("alice@example.com").orElseThrow());
        m.setName("Alice API");
        m.setUrl("https://example.com");
        monitors.save(m);
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

    private ResultActions create(String auth, String name) throws Exception {
        return mockMvc.perform(post("/api/api-keys")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("name", name))));
    }

    /** Creates a key and returns {id, secret}. */
    private JsonNode createKey(String auth, String name) throws Exception {
        String json = create(auth, name).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static String bearer(JsonNode created) {
        return "Bearer " + created.get("secret").asString();
    }

    @Test
    void createReturnsTheKeyOnceAndStoresOnlyItsHash() throws Exception {
        JsonNode created = createKey(alice, "  GitHub Actions  ");
        String secret = created.get("secret").asString();

        assertThat(secret).matches("pg_live_[A-Za-z0-9]{32}");
        assertThat(created.get("apiKey").get("name").asString()).isEqualTo("GitHub Actions");
        assertThat(created.get("apiKey").get("prefix").asString()).isEqualTo(secret.substring(0, 12));

        String listed = mockMvc.perform(get("/api/api-keys").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(secret, "hash");

        ApiKey stored = apiKeys.findAll().getFirst();
        assertThat(stored.getKeyHash()).isEqualTo(ApiKeySecrets.hash(secret)).doesNotContain(secret);
    }

    @Test
    void aKeyWorksAsABearerTokenForItsOwner() throws Exception {
        String key = bearer(createKey(alice, "CI"));

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Alice API"));
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void usingAKeyRecordsWhenItWasLastUsed() throws Exception {
        String key = bearer(createKey(alice, "CI"));

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key)).andExpect(status().isOk());

        mockMvc.perform(get("/api/api-keys").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());
    }

    @Test
    void anUnknownKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/monitors")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ApiKeySecrets.generate()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedKeyStopsWorkingOnTheNextRequest() throws Exception {
        JsonNode created = createKey(alice, "CI");
        String key = bearer(created);
        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/api-keys/" + created.get("apiKey").get("id").asLong())
                        .header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledAccountsKeysStopWorking() throws Exception {
        String key = bearer(createKey(alice, "CI"));
        User user = users.findByEmail("alice@example.com").orElseThrow();
        user.setEnabled(false);
        users.saveAndFlush(user);

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyCannotManageKeysChangeThePasswordOrReachBilling() throws Exception {
        String key = bearer(createKey(alice, "CI"));

        mockMvc.perform(get("/api/api-keys").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isForbidden());
        create(key, "Minted by a key").andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/password").header(HttpHeaders.AUTHORIZATION, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Sup3rSecret!","newPassword":"taken-over-123"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/billing/subscription").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isForbidden());

        assertThat(apiKeys.count()).isEqualTo(1);
    }

    @Test
    void keyManagementStillNeedsAuthentication() throws Exception {
        mockMvc.perform(get("/api/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantsKeyIsNotFound() throws Exception {
        JsonNode created = createKey(alice, "CI");

        mockMvc.perform(delete("/api/api-keys/" + created.get("apiKey").get("id").asLong())
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/api-keys").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(jsonPath("$.length()").value(0));

        // Bob's key sees Bob's (empty) account, never Alice's monitors.
        String bobsKey = bearer(createKey(bob, "CI"));
        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, bobsKey))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsABlankNameAndADuplicateInAnyCase() throws Exception {
        create(alice, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("Give the key a name so you know what uses it"));

        createKey(alice, "Deploy");
        create(alice, "deploy").andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors.name").value("You already have a key with that name"));
        // Names are per account.
        create(bob, "Deploy").andExpect(status().isCreated());
    }

    @Test
    void capsTheNumberOfKeys() throws Exception {
        for (int i = 0; i < ApiKeyService.MAX_KEYS; i++) {
            createKey(alice, "Key " + i);
        }
        create(alice, "One too many").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can have up to 10 keys. Revoke one you no longer use."));
    }

    @Test
    void changingThePasswordRevokesEveryKey() throws Exception {
        String key = bearer(createKey(alice, "CI"));
        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key)).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/password").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Sup3rSecret!","newPassword":"brand-new-password"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isUnauthorized());
        assertThat(apiKeys.count()).isZero();
    }
}
