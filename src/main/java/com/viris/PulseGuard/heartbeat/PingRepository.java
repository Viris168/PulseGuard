package com.viris.PulseGuard.heartbeat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PingRepository extends JpaRepository<Ping, Long> {

    /** Ping history in a range; the page carries the limit and sort. Served by (monitor_id, received_at). */
    List<Ping> findByMonitorIdAndReceivedAtBetween(Long monitorId, Instant from, Instant to, Pageable pageable);
}
