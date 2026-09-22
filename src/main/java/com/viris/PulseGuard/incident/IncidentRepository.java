package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.enumeration.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByMonitorIdAndStatus(Long monitorId, IncidentStatus status);

    // Tenant-scoped via monitor.user
    List<Incident> findAllByMonitorUserIdOrderByStartedAtDesc(Long userId);

    Optional<Incident> findByIdAndMonitorUserId(Long id, Long userId);

    List<Incident> findAllByMonitorIdOrderByStartedAtDesc(Long monitorId);
}
