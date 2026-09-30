package com.viris.PulseGuard.ai.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every lookup takes the user id, so another user's conversation is simply not found: the
 * caller can't tell it apart from one that doesn't exist.
 */
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);

    /** The conversation list, most recently used first. */
    List<AiConversation> findAllByUserIdOrderByUpdatedAtDesc(Long userId, Pageable page);

    /**
     * Retention: up to {@code limit} conversations of one plan's users not used since
     * {@code cutoff}. Their messages and feedback go with them (ON DELETE CASCADE). Called in a
     * loop, one short transaction per batch, like the other retention deletes.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            delete from ai_conversations where id in (
                select c.id from ai_conversations c
                join users u on u.id = c.user_id
                where u.plan = :plan and c.updated_at < :cutoff
                limit :limit)
            """)
    int deleteBatchForPlanBefore(@Param("plan") String plan, @Param("cutoff") Instant cutoff,
                                 @Param("limit") int limit);
}
