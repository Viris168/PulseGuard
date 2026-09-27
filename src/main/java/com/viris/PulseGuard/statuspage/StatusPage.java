package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "status_pages")
@Getter
@Setter
@NoArgsConstructor
public class StatusPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 280)
    private String description = "";

    @Column(nullable = false)
    private boolean published;

    /**
     * Replaced as a whole on save, never edited in place: Hibernate then deletes the old rows
     * before inserting the new ones, so reordering cannot trip the primary key. Ordered by an
     * explicit column rather than {@code @OrderColumn}, which turns the gap a deleted monitor
     * leaves into a null element.
     */
    @ElementCollection
    @CollectionTable(name = "status_page_monitors", joinColumns = @JoinColumn(name = "status_page_id"))
    @OrderBy("position")
    private List<StatusPageEntry> entries = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
