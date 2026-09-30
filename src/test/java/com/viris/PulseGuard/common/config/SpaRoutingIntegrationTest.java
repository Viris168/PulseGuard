package com.viris.PulseGuard.common.config;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.UserRepository;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built frontend is served from the API's own origin (SpaConfig). Uses the stand-in files in
 * src/test/resources/static. Runs on a real server so the welcome page, resource handlers,
 * security headers and container error handling all take part.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaRoutingIntegrationTest {

    private static final String SPA_MARKER = "PulseGuard test SPA";

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
    void rootServesTheAppWithoutASignIn() throws Exception {
        HttpResponse<String> response = get("/", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(SPA_MARKER);
    }

    @Test
    void clientSideRoutesServeTheAppSoAReloadWorks() throws Exception {
        for (String path : new String[]{"/monitors/5", "/incidents", "/status/shop", "/login"}) {
            HttpResponse<String> response = get(path, false);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains(SPA_MARKER);
            assertThat(response.headers().firstValue("Content-Type")).as(path).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/html"));
        }
    }

    @Test
    void indexHtmlIsNeverCachedSoANewDeployShowsAtOnce() throws Exception {
        assertThat(get("/monitors/5", false).headers().firstValue("Cache-Control"))
                .hasValue("no-cache");
    }

    @Test
    void hashedAssetsAreCachedForAYear() throws Exception {
        HttpResponse<String> response = get("/assets/app-test.js", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("test asset");
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("max-age=31536000", "immutable"));
    }

    @Test
    void aMissingAssetIsA404NotTheApp() throws Exception {
        HttpResponse<String> response = get("/assets/gone-3f2a.js", false);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(SPA_MARKER);
    }

    @Test
    void unknownApiPathsNeverFallBackToTheApp() throws Exception {
        HttpResponse<String> anonymous = get("/api/nope", false);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain(SPA_MARKER);

        HttpResponse<String> signedIn = get("/api/nope", true);
        assertThat(signedIn.statusCode()).isEqualTo(404);
        assertThat(signedIn.body()).doesNotContain(SPA_MARKER);
    }

    @Test
    void apiRulesStillApply() throws Exception {
        assertThat(get("/api/monitors", false).statusCode()).isEqualTo(401);
        assertThat(get("/api/monitors", true).statusCode()).isEqualTo(200);
    }

    @Test
    void actuatorStillRequiresAnAdmin() throws Exception {
        assertThat(get("/actuator/metrics", false).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/metrics", true).statusCode()).isEqualTo(403);
    }

    @Test
    void writesOutsideTheApiStillNeedASignIn() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/monitors/5"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
