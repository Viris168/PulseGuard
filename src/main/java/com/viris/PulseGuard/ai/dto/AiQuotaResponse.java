package com.viris.PulseGuard.ai.dto;

/** Questions asked today and the plan's daily limit; {@code limit} is null for unlimited plans. */
public record AiQuotaResponse(long used, Integer limit) {
}
