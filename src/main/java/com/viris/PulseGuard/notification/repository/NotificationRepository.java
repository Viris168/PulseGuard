package com.viris.PulseGuard.notification.repository;

import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByIncidentIdAndChannelIdAndEventType(Long incidentId, Long channelId, NotificationEventType eventType);

    List<Notification> findAllByIncidentId(Long incidentId);

    /** Failed alerts whose retry is due, oldest first. Partial index on next_attempt_at. */
    @Query("""
            select n.id from Notification n
            where n.status = com.viris.PulseGuard.enumeration.NotificationStatus.FAILED
              and n.nextAttemptAt <= :now
            order by n.nextAttemptAt
            """)
    List<Long> findRetryDueIds(@Param("now") Instant now, Pageable page);

    /**
     * Claims one due retry: FAILED back to PENDING, in a single conditional UPDATE, so of two
     * nodes sweeping at once exactly one gets 1 back and sends. Same idea as the insert claim.
     */
    @Modifying
    @Query("""
            update Notification n
            set n.status = com.viris.PulseGuard.enumeration.NotificationStatus.PENDING, n.nextAttemptAt = null,
                n.sentAt = :now
            where n.id = :id
              and n.status = com.viris.PulseGuard.enumeration.NotificationStatus.FAILED
              and n.nextAttemptAt <= :now
            """)
    int claimRetry(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Sends that never finished: PENDING since before {@code cutoff}, so the app died mid-send.
     * They become due retries. sentAt is the claim time for an in-flight send (claimRetry
     * refreshes it), so a send in progress is never mistaken for a stuck one.
     */
    @Modifying
    @Query("""
            update Notification n
            set n.status = com.viris.PulseGuard.enumeration.NotificationStatus.FAILED, n.nextAttemptAt = :now
            where n.status = com.viris.PulseGuard.enumeration.NotificationStatus.PENDING
              and n.sentAt < :cutoff
            """)
    int releaseStuck(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** A notification with everything a resend needs, for the retry path. */
    @Query("""
            select n from Notification n
            join fetch n.channel c
            join fetch n.incident i
            join fetch i.monitor m
            join fetch m.user
            where n.id = :id
            """)
    Optional<Notification> findForRetry(@Param("id") Long id);

    /** Timeline rows with their channel in one query (join fetch), oldest first. */
    @Query("""
            select n from Notification n
            join fetch n.channel
            where n.incident.id = :incidentId
            order by n.sentAt
            """)
    List<Notification> findTimelineByIncidentId(@Param("incidentId") Long incidentId);
}
