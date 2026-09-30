package com.viris.PulseGuard.common.config;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.PulseGuardApplication;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.heartbeat.HeartbeatProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prod profile (application-prod.yaml) as the container runs it: on a real server, since
 * forwarded headers are applied by Tomcat, not by MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "PULSEGUARD_PING_BASE_URL=https://ping.example.com")
@ActiveProfiles("prod")
class ProdProfileTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    /** Remembers which client IP each login attempt was counted against. */
    static class RecordingRateLimiter implements LoginRateLimiter {
        final List<String> clientIps = new CopyOnWriteArrayList<>();

        @Override
        public boolean tryAcquire(String email, String clientIp) {
            clientIps.add(clientIp);
            return true;
        }

        @Override
        public void reset(String email, String clientIp) {
        }
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
        RecordingRateLimiter rateLimiter() {
            return new RecordingRateLimiter();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    RecordingRateLimiter rateLimiter;
    @Autowired
    UserRepository users;
    @Autowired
    HeartbeatProperties heartbeatProperties;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        users.deleteAll();
        rateLimiter.clientIps.clear();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> login(String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"nobody@example.com","password":"wrong-password"}"""));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void profileLoadsWithTheConfiguredPingOrigin() {
        assertThat(heartbeatProperties.pingBaseUrl()).isEqualTo("https://ping.example.com");
    }

    @Test
    void loginRateLimitCountsTheRealClientBehindTheHostsProxy() throws Exception {
        // The test client connects from 127.0.0.1, a trusted proxy address, like the host's proxy.
        login("X-Forwarded-For", "203.0.113.7");
        login("X-Forwarded-For", "198.51.100.23, 10.0.0.5");

        assertThat(rateLimiter.clientIps).containsExactly("203.0.113.7", "198.51.100.23");
    }

    @Test
    void withoutAProxyTheConnectionAddressIsUsed() throws Exception {
        login();

        assertThat(rateLimiter.clientIps).containsExactly("127.0.0.1");
    }

    @Test
    void httpsAtTheProxyTurnsOnHsts() throws Exception {
        HttpResponse<String> response = login("X-Forwarded-Proto", "https");

        assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
    }

    @Test
    void probesArePublicAndRevealNoDetails() throws Exception {
        for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(probe)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(probe).isEqualTo(200);
            assertThat(response.body()).as(probe).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    void anEmptyPingBaseUrlStopsStartup() {
        // Command-line arguments, so they outrank a developer's .env as the container's variables would.
        SpringApplicationBuilder app = new SpringApplicationBuilder(PulseGuardApplication.class).profiles("prod");

        assertThatThrownBy(() -> app.run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--server.port=0",
                        "--PULSEGUARD_PING_BASE_URL=").close())
                .hasStackTraceContaining("pingBaseUrl");
    }

    @Test
    void anUnsetPingBaseUrlIsRejected() {
        // Unresolved placeholders reach binding as literal text, which @NotBlank alone would accept.
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new HeartbeatProperties("${PULSEGUARD_PING_BASE_URL}", Duration.ofSeconds(30))))
                .isNotEmpty();
        assertThat(validator.validate(new HeartbeatProperties("https://pulseguard.example.com", Duration.ofSeconds(30))))
                .isEmpty();
    }
}
