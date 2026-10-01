package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Errors that no {@code @ExceptionHandler} covers are forwarded by the servlet container to
 * {@code /error}. That second (ERROR) dispatch runs the security chain again, and it must not
 * turn a server error into a 401: the frontend reads a 401 as "session expired" and signs the
 * user out. MockMvc never performs that forward, so this runs against a real server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchSecurityIntegrationTest {

    private static final String INTERNAL_DETAIL = "internal detail that must not leak";

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

        @Bean
        BrokenController brokenController() {
            return new BrokenController();
        }
    }

    /** Stands in for any bug: an exception no handler maps. */
    @RestController
    static class BrokenController {
        @GetMapping("/api/test/broken")
        String broken() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        HttpResponse<String> registered = http.send(HttpRequest.newBuilder(uri("/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"Dara","email":"dara@example.com","password":"Sup3rSecret!"}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        token = objectMapper.readTree(registered.body()).get("token").asString();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (authenticated) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void unhandledErrorForASignedInUserIsA500NotA401() throws Exception {
        HttpResponse<String> response = get("/api/test/broken", true);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain(INTERNAL_DETAIL);
    }

    @Test
    void unknownPathForASignedInUserIsA404NotA401() throws Exception {
        assertThat(get("/api/does-not-exist", true).statusCode()).isEqualTo(404);
    }

    @Test
    void protectedPathWithoutATokenIsStillA401() throws Exception {
        assertThat(get("/api/test/broken", false).statusCode()).isEqualTo(401);
        assertThat(get("/api/does-not-exist", false).statusCode()).isEqualTo(401);
    }

    @Test
    void theErrorPageItselfStillNeedsAToken() throws Exception {
        assertThat(get("/error", false).statusCode()).isEqualTo(401);
    }
}
