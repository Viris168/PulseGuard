package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
