package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.chat.AiMessage;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;

import com.viris.PulseGuard.ai.tools.ToolCallRecord;

import java.time.Instant;
import java.util.List;

/**
 * One message as the panel shows it. {@code rating} is the user's thumbs up (1) or down (-1) on
 * an answer, null when not rated. {@code lookups}: what the model looked up for an answer, e.g.
 * "Checked uptime for Health, 2026-09-01 to 2026-09-03". Token counts stay on the server: they're
 * for cost tracking.
 */
public record MessageResponse(Long id, MessageRole role, String content, MessageStatus status,
                              Instant createdAt, Short rating, List<String> lookups,
                              List<ToolCallRecord.Source> sources) {

    public static MessageResponse from(AiMessage m, Short rating, List<String> lookups,
                                       List<ToolCallRecord.Source> sources) {
        return new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getStatus(), m.getCreatedAt(), rating,
                lookups, sources);
    }
}
