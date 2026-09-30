package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Failed alerts are retried with backoff, against real Postgres. The retry sweep is called
 * directly with a clock moved forward; SMTP is mocked to fail or succeed on demand.
 */
@SpringBootTest
class AlertRetryIntegrationTest {

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

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    NotificationService notificationService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    IncidentRepository incidents;

    private Incident incident;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        User user = users.save(User.builder().name("Alice").email("alice@example.com").passwordHash("x")
                .emailVerifiedAt(Instant.now()).build());
        NotificationChannel email = new NotificationChannel();
        email.setUser(user);
        email.setType(com.viris.PulseGuard.enumeration.ChannelType.EMAIL);
        email.setTarget("alice@example.com");
        channels.save(email);

        Monitor monitor = new Monitor();
        monitor.setUser(user);
        monitor.setName("API");
        monitor.setUrl("https://example.com");
        monitors.save(monitor);

        incident = new Incident();
        incident.setMonitor(monitor);
        incident.setCause("STATUS_MISMATCH: Expected 200 but got 500");
        incident.setStartedAt(Instant.now().minusSeconds(120));
        incidents.save(incident);
        clearInvocations(mailSender);
    }

    private Notification only() {
        return notifications.findAll().getFirst();
    }

    /** Pretends the retry's wait is over. */
    private Instant afterNextAttempt() {
        return only().getNextAttemptAt().plusSeconds(1);
    }

    @Test
    void aFailedAlertIsRetriedAndThenSent() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);

        Notification failed = only();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isBetween(Instant.now().plusSeconds(50), Instant.now().plusSeconds(70));

        // Not due yet: nothing happens.
        assertThat(notificationService.retryDue(Instant.now())).isZero();

        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        assertThat(notificationService.retryDue(afterNextAttempt())).isEqualTo(1);

        Notification sent = only();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getNextAttemptAt()).isNull();
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void retriesBackOffAndThenGiveUp() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);

        Duration[] expected = {Duration.ofMinutes(5), Duration.ofMinutes(30)};
        for (Duration wait : expected) {
            Instant before = Instant.now();
            notificationService.retryDue(afterNextAttempt());
            assertThat(only().getNextAttemptAt()).isAfter(before.plus(wait).minusSeconds(5));
        }
        notificationService.retryDue(afterNextAttempt()); // the third and last retry

        Notification given = only();
        assertThat(given.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(given.getAttempts()).isEqualTo(4);
        assertThat(given.getNextAttemptAt()).isNull();
        assertThat(notificationService.retryDue(Instant.now().plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    void aFailureNoRetryCanFixIsNotRetried() {
        doThrow(new MailParseException("bad address")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);

        assertThat(only().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(only().getNextAttemptAt()).isNull();
    }

    @Test
    void aDownAlertIsNotSentLateOnceTheIncidentHasResolved() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);
        Incident resolved = incidents.findById(incident.getId()).orElseThrow();
        resolved.setStatus(IncidentStatus.RESOLVED);
        resolved.setResolvedAt(Instant.now());
        incidents.save(resolved);
        clearInvocations(mailSender);

        notificationService.retryDue(afterNextAttempt());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(only().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(only().getNextAttemptAt()).isNull();
    }

    @Test
    void aRetryToAChannelSwitchedOffMeanwhileIsDropped() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);
        NotificationChannel channel = channels.findAll().getFirst();
        channel.setEnabled(false);
        channels.save(channel);
        clearInvocations(mailSender);

        notificationService.retryDue(afterNextAttempt());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(only().getNextAttemptAt()).isNull();
    }

    @Test
    void aRetryIsClaimedOnce() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(incident.getId(), NotificationEventType.OPENED);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        Instant due = afterNextAttempt();

        assertThat(notificationService.retryDue(due)).isEqualTo(1);
        assertThat(notificationService.retryDue(due)).isZero();
    }

    @Test
    void anAlertLeftPendingByACrashIsRetried() {
        NotificationChannel channel = channels.findAll().getFirst();
        Notification stuck = new Notification();
        stuck.setIncident(incident);
        stuck.setChannel(channel);
        stuck.setEventType(NotificationEventType.OPENED);
        stuck.setStatus(NotificationStatus.PENDING);
        stuck.setSentAt(Instant.now().minus(Duration.ofHours(1))); // claimed an hour ago, never finished
        notifications.save(stuck);

        assertThat(notificationService.retryDue(Instant.now())).isEqualTo(1);

        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(only().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void aSendInProgressIsNotMistakenForAStuckOne() {
        NotificationChannel channel = channels.findAll().getFirst();
        Notification inFlight = new Notification();
        inFlight.setIncident(incident);
        inFlight.setChannel(channel);
        inFlight.setEventType(NotificationEventType.OPENED);
        inFlight.setStatus(NotificationStatus.PENDING);
        inFlight.setSentAt(Instant.now().minusSeconds(5));
        notifications.save(inFlight);

        assertThat(notificationService.retryDue(Instant.now())).isZero();
        assertThat(only().getStatus()).isEqualTo(NotificationStatus.PENDING);
    }
}
