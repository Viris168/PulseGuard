package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "monitors")
@Getter
@Setter
@NoArgsConstructor
public class Monitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(nullable = false, length = 10)
    private String method = "GET";

    @Column(name = "expected_status", nullable = false)
    private int expectedStatus = 200;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds = 300;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 10000;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonitorState state = MonitorState.UP;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "consecutive_successes", nullable = false)
    private int consecutiveSuccesses;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
