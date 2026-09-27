package com.viris.PulseGuard.statuspage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One monitor on a status page. Holds the monitor's id, not the entity: the page only needs
 * the id to look the monitor up, and the foreign key (ON DELETE CASCADE) keeps it valid.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StatusPageEntry {

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private int position;
}
