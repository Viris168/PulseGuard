package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.ai.tools.ToolCallRecord;
import com.viris.PulseGuard.enumeration.MessageStatus;

import java.util.List;

/**
 * The data of each Server-Sent Event on the message stream; mirrors {@code streamMessage} in
 * frontend/src/api/ai.ts.
 */
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    /**
     * {@code event: tool}: something the model looked up, e.g. "Checked uptime for Health, …", and
     * for a help-docs search the sections it found (empty otherwise).
     */
    public record Tool(String label, List<ToolCallRecord.Source> sources) {
    }

    /** {@code event: delta}: the next piece of the answer. */
    public record Delta(String text) {
    }

    /** {@code event: done}: the answer is saved. */
    public record Done(Long questionId, Long answerId, MessageStatus status, AiQuotaResponse quota) {
    }

    /**
     * {@code event: error}: the answer ended early. {@code counted} is false when the question was
     * handed back to the daily limit; {@code answerId} is the saved partial answer, if any text came.
     */
    public record Error(String message, boolean counted, Long answerId) {
    }
}
