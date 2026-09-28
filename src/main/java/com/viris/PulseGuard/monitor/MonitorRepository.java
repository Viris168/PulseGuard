package com.viris.PulseGuard.monitor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    // Tenant-scoped: always use these in user-facing paths
    List<Monitor> findAllByUserId(Long userId);

    Optional<Monitor> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    /** Monitors checking more often than a plan allows, e.g. after a downgrade. */
    List<Monitor> findAllByUserIdAndIntervalSecondsLessThan(Long userId, int intervalSeconds);

    // Internal (scheduler) use only
    List<Monitor> findAllByActiveTrue();

    /** The public ping endpoint's lookup; the token is unique. */
    Optional<Monitor> findByHeartbeatToken(String heartbeatToken);

    /** Active heartbeats whose deadline has passed, most overdue first. Partial index on ping_deadline. */
    @Query("""
            select m.id from Monitor m
            where m.type = com.viris.PulseGuard.enumeration.MonitorType.HEARTBEAT
              and m.active = true and m.pingDeadline <= :now
            order by m.pingDeadline
            """)
    List<Long> findOverdueHeartbeatIds(@Param("now") Instant now, Pageable page);

    /**
     * Bulk update on purpose: it skips {@code @Version}, so a check finishing while the
     * owner edits the monitor cannot fail their save with an optimistic-lock conflict.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Monitor m SET m.lastCheckedAt = :checkedAt WHERE m.id = :id")
    int touchLastCheckedAt(@Param("id") Long id, @Param("checkedAt") Instant checkedAt);
}
