package com.viris.PulseGuard.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A thumbs up or down on one assistant message; at most one per message, and changing your mind
 * replaces it. Poorly rated answers become test cases for the prompt.
 */
@Entity
@Table(name = "ai_message_feedback")
@Getter
@Setter
@NoArgsConstructor
public class AiMessageFeedback {

    public static final short THUMBS_UP = 1;
    public static final short THUMBS_DOWN = -1;

    @Id
    @Column(name = "message_id")
    private Long messageId;

    /** {@link #THUMBS_UP} or {@link #THUMBS_DOWN}; the database rejects anything else. */
    @Column(nullable = false)
    private short rating;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AiMessageFeedback(Long messageId, short rating) {
        this.messageId = messageId;
        this.rating = rating;
    }
}
