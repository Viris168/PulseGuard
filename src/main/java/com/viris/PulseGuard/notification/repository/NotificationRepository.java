package com.viris.PulseGuard.notification.repository;

import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByIncidentIdAndChannelIdAndEventType(Long incidentId, Long channelId, NotificationEventType eventType);

    List<Notification> findAllByIncidentId(Long incidentId);

    /** Timeline rows with their channel in one query (join fetch), oldest first. */
    @Query("""
            select n from Notification n
            join fetch n.channel
            where n.incident.id = :incidentId
            order by n.sentAt
            """)
    List<Notification> findTimelineByIncidentId(@Param("incidentId") Long incidentId);
}
