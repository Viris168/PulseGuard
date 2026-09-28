package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.AuthService;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import com.viris.PulseGuard.scheduling.MonitorCheckService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Slack alerts end to end: a real outage (real Postgres, real HTTP target, real async listener)
 * reaching the real SlackSender. Only SMTP and Slack itself are replaced.
 */
@SpringBootTest
class SlackAlertFlowIntegrationTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/secretToken123";

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

        /** MockWebServer listens on loopback, which the real SSRF check rightly blocks. */
        @Bean
        @Primary
        SafeUrlValidator permissiveUrlValidator() {
            return new SafeUrlValidator(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        }
    }

    @MockitoBean
    JavaMailSender mailSender;

    @TestBean(name = "slackRestClient", methodName = "mockSlack")
    RestClient slackRestClient;
    static MockRestServiceServer slack;

    static RestClient mockSlack() {
        RestClient.Builder builder = RestClient.builder();
        slack = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }

    @Autowired
    AuthService authService;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    MonitorCheckService monitorCheckService;

    private MockWebServer target;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // everything else follows by ON DELETE CASCADE
        slack.reset();
        target = new MockWebServer();
        target.start();
    }

    @AfterEach
    void stopTarget() throws Exception {
        target.shutdown();
    }

    private User register(String email, Plan plan) {
        authService.register(new RegisterRequest("Owner", email, "Sup3rSecret!"));
        User user = users.findByEmail(email).orElseThrow();
        user.setPlan(plan);
        return users.save(user);
    }

    private void slackChannel(User owner) {
        NotificationChannel channel = new NotificationChannel();
        channel.setUser(owner);
        channel.setType(ChannelType.SLACK);
        channel.setTarget(WEBHOOK);
        channels.save(channel);
    }

    private Long monitorFor(User owner) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("Payments API");
        monitor.setUrl(target.url("/health").toString());
        monitor.setTimeoutMs(2000);
        return monitors.save(monitor).getId();
    }

    private void check(Long monitorId, int... statuses) {
        for (int status : statuses) {
            target.enqueue(new MockResponse().setResponseCode(status));
            monitorCheckService.runCheck(monitorId);
        }
    }

    /** Alerts finish asynchronously; wait until every row has left PENDING. */
    private Map<ChannelType, NotificationStatus> awaitOutcomes(int rows) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            var all = notifications.findAll();
            if (all.size() == rows && all.stream().noneMatch(n -> n.getStatus() == NotificationStatus.PENDING)) {
                // The channel is a lazy proxy here (no session); its id is readable, its type is not.
                Map<Long, ChannelType> types = channels.findAll().stream()
                        .collect(Collectors.toMap(NotificationChannel::getId, NotificationChannel::getType));
                return all.stream().collect(Collectors.toMap(n -> types.get(n.getChannel().getId()),
                        Notification::getStatus, (a, b) -> a == b ? a : NotificationStatus.PENDING));
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("alerts did not finish: " + notifications.findAll().size() + " rows");
    }

    @Test
    void outageOnAProPlanAlertsEmailAndSlackOnOpenAndRecovery() {
        User owner = register("owner@example.com", Plan.PRO);
        slackChannel(owner);
        Long monitorId = monitorFor(owner);
        slack.expect(ExpectedCount.once(), requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text").value("🔴 DOWN: Payments API"))
                .andExpect(jsonPath("$.blocks[1].fields[1].text").value("*Cause*\nSTATUS_MISMATCH: Expected 200 but got 500"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        slack.expect(ExpectedCount.once(), requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text").value("✅ RECOVERED: Payments API"))
                .andExpect(jsonPath("$.blocks[1].fields[1].text").value(org.hamcrest.Matchers.startsWith("*Down for*\n")))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        check(monitorId, 500, 500, 500);
        verify(mailSender, timeout(5000)).send(any(SimpleMailMessage.class));
        check(monitorId, 200, 200);

        verify(mailSender, timeout(5000).times(2)).send(any(SimpleMailMessage.class));
        slack.verify(Duration.ofSeconds(5));
        assertThat(awaitOutcomes(4)).containsEntry(ChannelType.SLACK, NotificationStatus.SENT)
                .containsEntry(ChannelType.EMAIL, NotificationStatus.SENT);
    }

    @Test
    void slackFailureIsRecordedAndEmailStillGoesOut() {
        User owner = register("owner@example.com", Plan.PRO);
        slackChannel(owner);
        Long monitorId = monitorFor(owner);
        slack.expect(requestTo(WEBHOOK))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("no_service"));

        check(monitorId, 500, 500, 500);

        verify(mailSender, timeout(5000)).send(any(SimpleMailMessage.class));
        slack.verify(Duration.ofSeconds(5));
        assertThat(awaitOutcomes(2)).containsEntry(ChannelType.SLACK, NotificationStatus.FAILED)
                .containsEntry(ChannelType.EMAIL, NotificationStatus.SENT);
    }

    @Test
    void slackChannelOnTheFreePlanIsNeverUsed() {
        User owner = register("owner@example.com", Plan.FREE);
        slackChannel(owner); // e.g. left enabled from before plan gating
        Long monitorId = monitorFor(owner);

        check(monitorId, 500, 500, 500);

        verify(mailSender, timeout(5000)).send(any(SimpleMailMessage.class));
        assertThat(awaitOutcomes(1)).containsOnlyKeys(ChannelType.EMAIL);
        verify(mailSender, after(500)).send(any(SimpleMailMessage.class));
        slack.verify(); // no request reached Slack
    }
}
