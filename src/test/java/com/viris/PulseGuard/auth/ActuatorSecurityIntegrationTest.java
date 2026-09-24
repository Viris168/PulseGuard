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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operational endpoints: health is public for the host's probes; metrics and Quartz state
 * are for operators only, through the real filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorSecurityIntegrationTest {

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
    }

    private static final String PASSWORD = "Sup3rSecret!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    ObjectMapper objectMapper;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void registerUserAndAdmin() throws Exception {
        users.deleteAll();
        userToken = tokenFrom(post("/api/auth/register"), """
                {"name":"User","email":"user@example.com","password":"%s"}""".formatted(PASSWORD));

        register("admin@example.com");
        User admin = users.findByEmail("admin@example.com").orElseThrow();
        admin.setRole(Role.ADMIN);
        users.save(admin);
        // Log in again: the role is baked into the token when it is issued.
        adminToken = tokenFrom(post("/api/auth/login"), """
                {"email":"admin@example.com","password":"%s"}""".formatted(PASSWORD));
    }

    private void register(String email) throws Exception {
        tokenFrom(post("/api/auth/register"), """
                {"name":"Admin","email":"%s","password":"%s"}""".formatted(email, PASSWORD));
    }

    private String tokenFrom(MockHttpServletRequestBuilder request, String body) throws Exception {
        String json = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asString();
    }

    @Test
    void healthIsPublic() throws Exception {
        // Reachable without a token. The status itself may be DOWN (503) here, because the
        // test environment has no Redis; what matters is a health answer, not a 401/403.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void metricsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsRejectNormalUsers() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsAllowAdmins() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }

    @Test
    void quartzEndpointAllowsAdmins() throws Exception {
        mockMvc.perform(get("/actuator/quartz").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void quartzEndpointRejectsNormalUsers() throws Exception {
        mockMvc.perform(get("/actuator/quartz").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexposedEndpointsStayHiddenEvenForAdmins() throws Exception {
        // env would leak configuration; it is not in the exposure list.
        mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
