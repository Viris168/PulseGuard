package com.viris.PulseGuard.ai.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Scoped through the conversation's owner in every query that serves a user, never by message
 * or conversation id alone (CLAUDE.md, tenant isolation).
 */
public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    /** A conversation's messages, oldest first: what the panel shows. */
    List<AiMessage> findAllByConversationIdAndConversationUserIdOrderByIdAsc(Long conversationId, Long userId);

    /** The newest messages first, as many as the page allows: the prompt's history. */
    List<AiMessage> findAllByConversationIdAndConversationUserIdOrderByIdDesc(Long conversationId, Long userId,
                                                                             Pageable page);

    /** For the per-conversation message cap. */
    long countByConversationId(Long conversationId);

    /** For feedback: only messages in the caller's own conversations. */
    Optional<AiMessage> findByIdAndConversationUserId(Long id, Long userId);
}
