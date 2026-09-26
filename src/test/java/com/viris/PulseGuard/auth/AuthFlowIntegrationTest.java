package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real filter chain: what a client actually gets back for each token state.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** Redis is not needed to prove the auth flow; the contracts are the same. */
    @TestConfiguration
    static class InMemoryBackends {
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
    ObjectMapper objectMapper;

    private String accessToken;
    private String refreshToken;
    private Long userId;

    @BeforeEach
    void register() throws Exception {
        users.deleteAll();
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Viris","email":"a@example.com","password":"secret123"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        accessToken = json.get("token").asString();
        refreshToken = json.get("refreshToken").asString();
        userId = json.get("user").get("id").asLong();
    }

    @Test
    void acceptsAValidAccessToken() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@example.com"));
    }

    @Test
    void rejectsAMissingTokenWith401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void rejectsATamperedTokenWith401() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + tamperSignature(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Changes the signature's first character. Not the last: in base64url the final character
     * of an HMAC-SHA256 signature carries only 4 real bits, so swapping it can leave the
     * decoded bytes unchanged and the token still valid, which made this test flaky.
     */
    private static String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    @Test
    void rejectsARefreshTokenUsedAsAnAccessToken() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheAccessTokenImmediately() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshReturnsAWorkingAccessToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String rotated = objectMapper.readTree(body).get("token").asString();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + rotated))
                .andExpect(status().isOk());
    }

    @Test
    void refreshRetiresTheAccessTokenWhenTheClientSendsIt() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshLeavesTheAccessTokenAliveWhenTheClientOmitsIt() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void aDisabledAccountLosesAccessOnTheNextRequest() throws Exception {
        User user = users.findById(userId).orElseThrow();
        user.setEnabled(false);
        users.saveAndFlush(user);

        // The token is still cryptographically valid; the database says otherwise.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleChangeTakesEffectWithoutReissuingTheToken() throws Exception {
        User user = users.findById(userId).orElseThrow();
        user.setRole(Role.ADMIN);
        users.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(users.findById(userId).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void changingThePasswordRevokesOtherSessionsButKeepsTheCaller() throws Exception {
        Thread.sleep(1100); // iat has second precision; make the cut-off strictly later.

        String body = mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"brand-new-password"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String rotated = objectMapper.readTree(body).get("token").asString();

        // The token the caller arrived with is dead; the one they were just handed works.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + rotated))
                .andExpect(status().isOk());

        // The refresh token from the old session is dead too, without ever being presented.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordRequiresTheCurrentOne() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong","newPassword":"brand-new-password"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordNeedsAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"brand-new-password"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theNewPasswordIsWhatLoginAccepts() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"brand-new-password"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@example.com","password":"secret123"}"""))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@example.com","password":"brand-new-password"}"""))
                .andExpect(status().isOk());
    }
}
