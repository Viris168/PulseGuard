package com.viris.PulseGuard.apikey.dto;

import com.viris.PulseGuard.apikey.ApiKey;

import java.time.Instant;

/** Mirrors {@code ApiKey} in frontend/src/types/apiKey.ts. Never carries the key or its hash. */
public record ApiKeyResponse(Long id, String name, String prefix, Instant createdAt, Instant lastUsedAt) {

    public static ApiKeyResponse from(ApiKey key) {
        return new ApiKeyResponse(key.getId(), key.getName(), key.getPrefix(), key.getCreatedAt(), key.getLastUsedAt());
    }
}
