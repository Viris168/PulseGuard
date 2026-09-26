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
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.incident.dto.IncidentOpenedEvent;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Critical Domain Rule 4 end to end: real Postgres, real HTTP target, real async listener.
 * Only the SMTP client is mocked, so what would be sent can be inspected.
 */
@SpringBootTest
class NotificationFlowIntegrationTest {

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

    @Autowired
    AuthService authService;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    NotificationChannelRepository channels;
    /** A spy: real repository, but a single method can be overridden for one test. */
    @MockitoSpyBean
    NotificationRepository notifications;
    @Autowired
    MonitorCheckService monitorCheckService;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    NotificationService notificationService;
    @Autowired
    PlatformTransactionManager txManager;

    private MockWebServer target;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // everything else follows by ON DELETE CASCADE
        target = new MockWebServer();
        target.start();
    }

    @AfterEach
    void stopTarget() throws Exception {
        target.shutdown();
    }

    private User register(String email) {
        authService.register(new RegisterRequest("Owner", email, "Sup3rSecret!"));
        return users.findByEmail(email).orElseThrow();
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

    @Test
    void registrationCreatesDefaultEmailChannel() {
        User owner = register("owner@example.com");

        List<NotificationChannel> created = channels.findAllByUserId(owner.getId());
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().getType()).isEqualTo(ChannelType.EMAIL);
        assertThat(created.getFirst().getTarget()).isEqualTo("owner@example.com");
    }

    @Test
    void outageEmailsTheOwnerOnceOnOpenAndOnceOnRecovery() {
        Long monitorId = monitorFor(register("owner@example.com"));

        check(monitorId, 500, 500);
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));

        check(monitorId, 500);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("🔴 DOWN: Payments API");
        assertThat(mail.getValue().getTo()).containsExactly("owner@example.com");

        check(monitorId, 500); // still down: no second "down" email
        check(monitorId, 200, 200);
        verify(mailSender, timeout(5000).times(2)).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("✅ RECOVERED: Payments API");

        // Both sends are logged; the async listener finishes the row a moment after the mail call.
        awaitSentRows(2);
        assertThat(notifications.findAll()).extracting(Notification::getStatus)
                .containsOnly(NotificationStatus.SENT);
        verify(mailSender, after(500).times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void rolledBackIncidentSendsNoEmail() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            events.publishEvent(new IncidentOpenedEvent(1L, 1L));
            status.setRollbackOnly(); // e.g. a @Version conflict after the event was published
        });

        verify(mailSender, after(1000).never()).send(any(SimpleMailMessage.class));
    }

    private long sentRows() {
        return notifications.findAll().stream().filter(n -> n.getStatus() == NotificationStatus.SENT).count();
    }

    @Test
    void concurrentDeliveriesOfTheSameAlertSendExactlyOnce() throws Exception {
        User owner = register("owner@example.com");
        Incident incident = new Incident();
        incident.setMonitor(monitors.findById(monitorFor(owner)).orElseThrow());
        incident.setCause("TIMEOUT: No response");
        Long incidentId = incidents.save(incident).getId();
        // Worst interleaving: both threads pass the cheap pre-check at the same moment, so the
        // claim's unique constraint is the only thing standing between us and a duplicate.
        doReturn(false).when(notifications)
                .existsByIncidentIdAndChannelIdAndEventType(any(), any(), any());
        // A slow SMTP server widens the window in which both deliveries are in flight.
        doAnswer(call -> {
            Thread.sleep(300);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                start.await();
                notificationService.notify(incidentId, NotificationEventType.OPENED);
                return null;
            });
        }
        start.countDown(); // both threads go at the same moment
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(notifications.findAll()).hasSize(1);
    }

    private void awaitSentRows(int expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (sentRows() < expected && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(sentRows()).isEqualTo(expected);
    }
}
