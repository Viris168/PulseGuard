package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.enumeration.NotificationEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByIncidentIdAndChannelIdAndEventType(Long incidentId, Long channelId, NotificationEventType eventType);

    List<Notification> findAllByIncidentId(Long incidentId);
}
