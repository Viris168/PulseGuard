package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import com.viris.PulseGuard.notification.repository.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final Long INCIDENT_ID = 7L;
    private static final Long USER_ID = 1L;

    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private NotificationChannelRepository channelRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationSender emailSender;

    private NotificationService service;
    private Incident incident;
    /** Stand-in for the notifications table: what saveAndFlush stored, findById returns. */
    private final List<Notification> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(emailSender.type()).thenReturn(ChannelType.EMAIL);
        // A mock transaction manager: the callbacks run, transactions are simply no-ops.
        service = new NotificationService(incidentRepository, channelRepository, notificationRepository,
                new AlertMessageFactory(), List.of(emailSender), mock(PlatformTransactionManager.class));

        User owner = User.builder().id(USER_ID).email("owner@example.com").build();
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("Payments API");
        monitor.setUrl("https://api.example.com/health");
        incident = new Incident();
        incident.setId(INCIDENT_ID);
        incident.setMonitor(monitor);
        incident.setCause("TIMEOUT: No response");
        incident.setStartedAt(Instant.parse("2026-09-27T03:12:45Z"));
        when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));

        AtomicLong ids = new AtomicLong(100);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(call -> {
            Notification record = call.getArgument(0);
            record.setId(ids.incrementAndGet());
            table.add(record);
            return record;
        });
        when(notificationRepository.findById(anyLong())).thenAnswer(call -> table.stream()
                .filter(r -> r.getId().equals(call.getArgument(0))).findFirst());
    }

    private NotificationChannel channel(long id, ChannelType type, String target) {
        NotificationChannel channel = new NotificationChannel();
        channel.setId(id);
        channel.setType(type);
        channel.setTarget(target);
        return channel;
    }

    private void userHasChannels(NotificationChannel... channels) {
        when(channelRepository.findAllByUserIdAndEnabledTrue(USER_ID)).thenReturn(List.of(channels));
    }

    @Test
    void sendsOpenedAlertToEveryEnabledChannel() {
        userHasChannels(channel(10, ChannelType.EMAIL, "a@example.com"), channel(11, ChannelType.EMAIL, "b@example.com"));

        service.notify(INCIDENT_ID, NotificationEventType.OPENED);

        ArgumentCaptor<AlertMessage> message = ArgumentCaptor.forClass(AlertMessage.class);
        verify(emailSender).send(eq("a@example.com"), message.capture());
        verify(emailSender).send(eq("b@example.com"), any());
        assertThat(message.getValue().subject()).isEqualTo("🔴 DOWN: Payments API");
        assertThat(table).extracting(Notification::getStatus).containsOnly(NotificationStatus.SENT);
    }

    @Test
    void claimsBeforeSending() {
        userHasChannels(channel(10, ChannelType.EMAIL, "a@example.com"));

        service.notify(INCIDENT_ID, NotificationEventType.OPENED);

        InOrder order = inOrder(notificationRepository, emailSender);
        order.verify(notificationRepository).saveAndFlush(any(Notification.class));
        order.verify(emailSender).send(eq("a@example.com"), any());
    }

    @Test
    void skipsWhenAnotherDeliveryClaimedFirst() {
        userHasChannels(channel(10, ChannelType.EMAIL, "a@example.com"));
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        service.notify(INCIDENT_ID, NotificationEventType.OPENED);

        verify(emailSender, never()).send(any(), any());
    }

    @Test
    void skipsChannelsAlreadyNotified() {
        userHasChannels(channel(10, ChannelType.EMAIL, "a@example.com"));
        when(notificationRepository.existsByIncidentIdAndChannelIdAndEventType(
                INCIDENT_ID, 10L, NotificationEventType.OPENED)).thenReturn(true);

        service.notify(INCIDENT_ID, NotificationEventType.OPENED);

        verify(emailSender, never()).send(any(), any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordsFailureAndContinuesWithOtherChannels() {
        userHasChannels(channel(10, ChannelType.EMAIL, "broken@example.com"), channel(11, ChannelType.EMAIL, "b@example.com"));
        doThrow(new IllegalStateException("SMTP down")).when(emailSender).send(eq("broken@example.com"), any());

        service.notify(INCIDENT_ID, NotificationEventType.OPENED);

        verify(emailSender).send(eq("b@example.com"), any());
        assertThat(table).extracting(Notification::getStatus)
                .containsExactly(NotificationStatus.FAILED, NotificationStatus.SENT);
    }

    @Test
    void skipsChannelTypesWithoutASender() {
        userHasChannels(channel(12, ChannelType.SLACK, "https://hooks.slack.com/secret"),
                channel(10, ChannelType.EMAIL, "a@example.com"));

        assertThatCode(() -> service.notify(INCIDENT_ID, NotificationEventType.OPENED)).doesNotThrowAnyException();

        verify(emailSender).send(eq("a@example.com"), any());
        assertThat(table).hasSize(1);
    }

    @Test
    void resolvedEventSendsRecoveryMessage() {
        incident.setResolvedAt(incident.getStartedAt().plusSeconds(870));
        userHasChannels(channel(10, ChannelType.EMAIL, "a@example.com"));

        service.notify(INCIDENT_ID, NotificationEventType.RESOLVED);

        ArgumentCaptor<AlertMessage> message = ArgumentCaptor.forClass(AlertMessage.class);
        verify(emailSender).send(eq("a@example.com"), message.capture());
        assertThat(message.getValue().subject()).isEqualTo("✅ RECOVERED: Payments API");
        assertThat(table.getFirst().getEventType()).isEqualTo(NotificationEventType.RESOLVED);
    }

    @Test
    void ignoresIncidentDeletedBeforeAlerting() {
        when(incidentRepository.findById(anyLong())).thenReturn(Optional.empty());

        service.notify(99L, NotificationEventType.OPENED);

        verify(emailSender, never()).send(any(), any());
    }
}
