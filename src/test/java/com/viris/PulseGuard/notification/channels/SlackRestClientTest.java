package com.viris.PulseGuard.notification.channels;

import com.viris.PulseGuard.notification.NotificationConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production RestClient bean against a real local server: redirects and timeouts. */
class SlackRestClientTest {

    private MockWebServer server;
    private RestClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new NotificationConfig().slackRestClient(
                new SlackProperties(Duration.ofSeconds(1), Duration.ofMillis(300)));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void neverFollowsARedirect() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")));
        server.enqueue(new MockResponse().setResponseCode(200));

        int status = client.post().uri(server.url("/services/T/B/x").uri()).body("{}")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(302);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void givesUpOnASlowResponse() {
        server.enqueue(new MockResponse().setBody("ok").setHeadersDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.post().uri(server.url("/services/T/B/x").uri()).body("{}")
                .retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }
}
