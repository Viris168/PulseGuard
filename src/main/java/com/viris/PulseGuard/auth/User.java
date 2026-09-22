package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    // No setter: assigned by the database, never by application code.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Setter
    @Column(nullable = false, length = 100)
    private String name;

    @Setter
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Setter
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan = Plan.FREE;

    @Setter
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    /** A disabled account cannot log in and its existing tokens stop working. */
    @Setter
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Set by lockout policy; separate from {@link #enabled} so the reason stays legible. */
    @Setter
    @Builder.Default
    @Column(nullable = false)
    private boolean locked = false;

    @Setter
    @Column(name = "stripe_customer_id", length = 100)
    private String stripeCustomerId;

    // No setter: written by the database default (see V1__init.sql).
    // @Generated makes Hibernate read the value back after INSERT instead of leaving it null.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
