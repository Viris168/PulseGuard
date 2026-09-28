package com.viris.PulseGuard.heartbeat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.List;

public interface PingRepository extends JpaRepository<Ping, Long> {

    /** Ping history in a range; the page carries the limit and sort. Served by (monitor_id, received_at). */
    List<Ping> findByMonitorIdAndReceivedAtBetween(Long monitorId, Instant from, Instant to, Pageable pageable);

    /** Retention: up to {@code limit} pings of one plan's monitors older than {@code cutoff}; see CheckRepository. */
    @Modifying
    @Query(nativeQuery = true, value = """
            delete from pings where id in (
                select p.id from pings p
                join monitors m on m.id = p.monitor_id
                join users u on u.id = m.user_id
                where u.plan = :plan and p.received_at < :cutoff
                limit :limit)
            """)
    int deleteBatchForPlanBefore(@Param("plan") String plan, @Param("cutoff") Instant cutoff,
                                 @Param("limit") int limit);
}
