package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByIncidentIdAndChannelIdAndEventType(Long incidentId, Long channelId, NotificationEventType eventType);

    List<Notification> findAllByIncidentId(Long incidentId);
}
