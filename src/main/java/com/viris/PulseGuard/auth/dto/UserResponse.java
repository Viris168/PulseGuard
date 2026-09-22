package com.viris.PulseGuard.auth.dto;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.Role;

import java.time.Instant;

public record UserResponse(
        Long id,
        String name,
        String email,
        Plan plan,
        Role role,
        Instant createdAt
) {

}