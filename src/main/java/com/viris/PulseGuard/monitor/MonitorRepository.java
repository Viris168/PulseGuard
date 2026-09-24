package com.viris.PulseGuard.monitor;

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

    // Internal (scheduler) use only
    List<Monitor> findAllByActiveTrue();

    /**
     * Bulk update on purpose: it skips {@code @Version}, so a check finishing while the
     * owner edits the monitor cannot fail their save with an optimistic-lock conflict.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Monitor m SET m.lastCheckedAt = :checkedAt WHERE m.id = :id")
    int touchLastCheckedAt(@Param("id") Long id, @Param("checkedAt") Instant checkedAt);
}
