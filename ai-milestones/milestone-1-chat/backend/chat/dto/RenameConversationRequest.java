package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.chat.AiConversation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameConversationRequest(
        @NotBlank(message = "Give the chat a name")
        @Size(max = AiConversation.MAX_TITLE_LENGTH, message = "Keep the name under 100 characters")
        String title
) {
}
