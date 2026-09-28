package com.viris.PulseGuard.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    List<ApiKey> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ApiKey> findByIdAndUserId(Long id, Long userId);

    Optional<ApiKey> findByKeyHash(String keyHash);

    long countByUserId(Long userId);

    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

    /**
     * Records use, at most once per {@code since} window: a script calling in a loop must not
     * turn every read into a write. Returns 0 when the stored time is already recent enough.
     */
    @Modifying
    @Query("""
            update ApiKey k set k.lastUsedAt = :now
            where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :since)
            """)
    int touch(@Param("id") Long id, @Param("now") Instant now, @Param("since") Instant since);
}
