package com.viris.PulseGuard.ai.chat.dto;

import jakarta.validation.constraints.NotNull;

/** 1 = thumbs up, -1 = thumbs down. Rating again replaces the earlier rating. */
public record FeedbackRequest(@NotNull(message = "Choose thumbs up or down") Short rating) {
}
