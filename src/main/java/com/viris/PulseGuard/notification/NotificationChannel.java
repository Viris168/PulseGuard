package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notification_channels")
@Getter
@Setter
@NoArgsConstructor
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String target;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Email only: when the address confirmed it wants this account's alerts. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Whether alerts may go to this channel's target. Non-email channels point at something the
     * owner controls (their Slack webhook). An email address must have confirmed, unless it is
     * the owner's own address and that is verified.
     */
    public boolean confirmedFor(User owner) {
        if (type != ChannelType.EMAIL) {
            return true;
        }
        return verifiedAt != null || (owner.isEmailVerified() && target.equals(owner.getEmail()));
    }
}
