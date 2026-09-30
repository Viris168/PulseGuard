package com.viris.PulseGuard.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by message id. Callers check the message is the user's first (AiMessageRepository). */
public interface AiMessageFeedbackRepository extends JpaRepository<AiMessageFeedback, Long> {
}
