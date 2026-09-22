package com.viris.PulseGuard.check;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CheckRepository extends JpaRepository<Check, Long> {

    List<Check> findByMonitorIdOrderByCheckedAtDesc(Long monitorId, Pageable pageable);

    List<Check> findByMonitorIdAndCheckedAtBetween(Long monitorId, Instant from, Instant to);

    /** Retention job: delete raw checks older than the cutoff. */
    @Modifying
    @Query("delete from Check c where c.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
