package com.viris.PulseGuard.statuspage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StatusPageRepository extends JpaRepository<StatusPage, Long> {

    Optional<StatusPage> findByUserId(Long userId);

    Optional<StatusPage> findBySlug(String slug);

    boolean existsBySlugAndUserIdNot(String slug, Long userId);
}
