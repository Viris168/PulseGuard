package com.viris.PulseGuard.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    // Tenant-scoped: always use these in user-facing paths
    List<Monitor> findAllByUserId(Long userId);

    Optional<Monitor> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    // Internal (scheduler) use only
    List<Monitor> findAllByActiveTrue();
}
