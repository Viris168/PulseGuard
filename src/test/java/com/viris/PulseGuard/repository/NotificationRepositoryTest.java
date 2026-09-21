package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.model.Incident;
import com.viris.PulseGuard.model.Notification;
import com.viris.PulseGuard.model.NotificationChannel;
import com.viris.PulseGuard.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    NotificationRepository notifications;

    Incident incident;
    NotificationChannel channel;

    @BeforeEach
    void setUp() {
        User user = newUser("a@example.com");
        incident = newIncident(newMonitor(user, "m"), IncidentStatus.OPEN);
        channel = newChannel(user, ChannelType.EMAIL, true);
    }

    private Notification notification(NotificationEventType type) {
        Notification n = new Notification();
        n.setIncident(incident);
        n.setChannel(channel);
        n.setEventType(type);
        n.setStatus(NotificationStatus.SENT);
        return n;
    }

    @Test
    void detectsAlreadySentNotification() {
        notifications.saveAndFlush(notification(NotificationEventType.OPENED));

        assertThat(notifications.existsByIncidentIdAndChannelIdAndEventType(
                incident.getId(), channel.getId(), NotificationEventType.OPENED)).isTrue();
        assertThat(notifications.existsByIncidentIdAndChannelIdAndEventType(
                incident.getId(), channel.getId(), NotificationEventType.RESOLVED)).isFalse();
    }

    @Test
    void rejectsDuplicateNotificationForSameIncidentChannelAndEvent() {
        notifications.saveAndFlush(notification(NotificationEventType.OPENED));

        assertThatThrownBy(() -> notifications.saveAndFlush(notification(NotificationEventType.OPENED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOpenedAndResolvedNotificationsForSameIncident() {
        notifications.saveAndFlush(notification(NotificationEventType.OPENED));
        notifications.saveAndFlush(notification(NotificationEventType.RESOLVED));

        assertThat(notifications.findAllByIncidentId(incident.getId())).hasSize(2);
    }
}
