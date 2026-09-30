package com.viris.PulseGuard.ai;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A user's Ask AI consent: whether their monitoring data may be sent to the AI provider, and
 * which monitors. {@code monitorIds} only matters when {@code allMonitors} is false.
 */
@Entity
@Table(name = "ai_access")
@Getter
@Setter
@NoArgsConstructor
public class AiAccess {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "all_monitors", nullable = false)
    private boolean allMonitors = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_access_monitors", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "monitor_id", nullable = false)
    private Set<Long> monitorIds = new HashSet<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AiAccess(Long userId) {
        this.userId = userId;
    }

    /** Whether this monitor may be sent to the provider. */
    public boolean allows(Long monitorId) {
        return enabled && (allMonitors || monitorIds.contains(monitorId));
    }
}
