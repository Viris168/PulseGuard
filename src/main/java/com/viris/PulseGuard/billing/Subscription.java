package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "stripe_subscription_id", unique = true, length = 100)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    /** Stripe status string: active, past_due, canceled... */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;
}
