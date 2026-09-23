package com.viris.PulseGuard.auth.jwt;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.dto.UserResponse;

import java.util.Locale;

public final class UserMapper {

    private UserMapper() {
    }

    public static User toEntity(RegisterRequest request, String passwordHash) {
        return User.builder()
                .name(request.name().trim())
                .email(normalizeEmail(request.email()))
                .passwordHash(passwordHash)
                .build();
    }

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPlan(),
                user.getRole(),
                user.getCreatedAt()
        );
    }

    /** Emails are stored lower-cased so the unique index actually prevents duplicates. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
