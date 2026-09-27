package com.viris.PulseGuard.billing.repository;

import com.viris.PulseGuard.billing.StripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StripeEventRepository extends JpaRepository<StripeEvent, String> {

    /**
     * Claims an event for processing. Two deliveries of the same event racing each other both
     * pass an {@code existsById} check; only one of them can insert the primary key. The loser
     * waits for the winner's transaction and gets 0 if it committed, 1 if it rolled back.
     *
     * @return 1 if this call recorded the event, 0 if it was already recorded
     */
    @Modifying
    @Query(value = "INSERT INTO stripe_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId);
}
