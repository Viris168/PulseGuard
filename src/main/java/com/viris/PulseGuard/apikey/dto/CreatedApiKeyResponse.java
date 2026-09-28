package com.viris.PulseGuard.apikey.dto;

/** The one response that contains the key itself; after this only its hash exists. */
public record CreatedApiKeyResponse(ApiKeyResponse apiKey, String secret) {
}
