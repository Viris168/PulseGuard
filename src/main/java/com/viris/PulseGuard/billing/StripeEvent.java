package com.viris.PulseGuard.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "stripe_events")
@Getter
@Setter
@NoArgsConstructor
public class StripeEvent {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public StripeEvent(String eventId) {
        this.eventId = eventId;
    }
}
