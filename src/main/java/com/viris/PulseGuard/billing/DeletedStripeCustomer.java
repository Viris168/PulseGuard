package com.viris.PulseGuard.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A Stripe customer left behind by a deleted account; its late webhooks are acknowledged. */
@Entity
@Table(name = "deleted_stripe_customers")
@Getter
@NoArgsConstructor
public class DeletedStripeCustomer {

    @Id
    @Column(name = "customer_id", length = 100)
    private String customerId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt = Instant.now();

    public DeletedStripeCustomer(String customerId) {
        this.customerId = customerId;
    }
}
