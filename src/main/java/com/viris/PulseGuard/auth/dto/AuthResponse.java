package com.viris.PulseGuard.auth.dto;

public record AuthResponse(
        String token,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
    public static AuthResponse of(String token, String refreshToken, long expiresIn, UserResponse user) {
        return new AuthResponse(token, refreshToken, "Bearer", expiresIn, user);
    }
}
