package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The /docs page's data through the real filter chain, with no token at all. */
@SpringBootTest
@AutoConfigureMockMvc
class HelpApiIntegrationTest {

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

    @Test
    void listsEveryArticleSignedOut() throws Exception {
        mockMvc.perform(get("/api/help"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.length()").value(16))
                .andExpect(jsonPath("$[?(@.slug == 'slack-alerts')].title").value("Slack alerts"))
                .andExpect(jsonPath("$[0].summary").isNotEmpty())
                .andExpect(jsonPath("$[0].markdown").doesNotExist());
    }

    @Test
    void returnsOneArticleWithoutItsFrontMatterSignedOut() throws Exception {
        mockMvc.perform(get("/api/help/slack-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("slack-alerts"))
                .andExpect(jsonPath("$.title").value("Slack alerts"))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.markdown", startsWith("On the **Pro** and **Business** plans")))
                .andExpect(jsonPath("$.markdown", containsString("\n## Setting it up\n")))
                .andExpect(jsonPath("$.markdown", not(containsString("describes:"))));
    }

    @Test
    void anUnknownArticleIsA404() throws Exception {
        mockMvc.perform(get("/api/help/no-such-article"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Help article not found: no-such-article"));
    }

    @Test
    void onlyReadingIsPublic() throws Exception {
        mockMvc.perform(post("/api/help")).andExpect(status().isUnauthorized());
    }
}
