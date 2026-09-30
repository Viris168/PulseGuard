package com.viris.PulseGuard.ai;

import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by user id, so every lookup is already scoped to one tenant. */
public interface AiAccessRepository extends JpaRepository<AiAccess, Long> {
}
