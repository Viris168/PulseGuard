package com.viris.PulseGuard.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Looked up by answer ids that came from the caller's own conversation (ConversationService). */
public interface AiToolCallRepository extends JpaRepository<AiToolCall, Long> {

    List<AiToolCall> findAllByMessageIdInOrderByIdAsc(Collection<Long> messageIds);
}
