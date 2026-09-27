package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.enumeration.IncidentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByMonitorIdAndStatus(Long monitorId, IncidentStatus status);

    // Tenant-scoped via monitor.user
    List<Incident> findAllByMonitorUserIdOrderByStartedAtDesc(Long userId);

    Optional<Incident> findByIdAndMonitorUserId(Long id, Long userId);

    List<Incident> findAllByMonitorIdOrderByStartedAtDesc(Long monitorId);

    /**
     * The incidents list, tenant-scoped through the monitor's owner. A null filter means "any".
     * {@code join fetch} loads each incident's monitor in the same query: without it, reading
     * the monitor name would cost one extra query per incident (N+1).
     */
    @Query("""
            select i from Incident i
            join fetch i.monitor m
            where m.user.id = :userId
              and (:status is null or i.status = :status)
              and (:monitorId is null or m.id = :monitorId)
            order by i.startedAt desc
            """)
    List<Incident> search(@Param("userId") Long userId,
                          @Param("status") IncidentStatus status,
                          @Param("monitorId") Long monitorId,
                          Pageable page);

    /** Incidents that were open at any moment of [from, to): started before its end, not resolved before its start. */
    @Query("""
            select i from Incident i
            where i.monitor.id = :monitorId
              and i.startedAt < :windowEnd
              and (i.resolvedAt is null or i.resolvedAt > :windowStart)
            """)
    List<Incident> findOverlapping(@Param("monitorId") Long monitorId,
                                   @Param("windowStart") Instant windowStart,
                                   @Param("windowEnd") Instant windowEnd);

    /** {@link #findOverlapping} for several monitors at once. */
    @Query("""
            select i from Incident i
            where i.monitor.id in :monitorIds
              and i.startedAt < :windowEnd
              and (i.resolvedAt is null or i.resolvedAt > :windowStart)
            """)
    List<Incident> findOverlappingForMonitors(@Param("monitorIds") Collection<Long> monitorIds,
                                              @Param("windowStart") Instant windowStart,
                                              @Param("windowEnd") Instant windowEnd);
}
