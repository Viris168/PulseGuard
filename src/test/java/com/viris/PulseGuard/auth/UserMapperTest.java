package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.mapper.UserMapper;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    @Test
    void lowercasesEmailButPreservesNameCasing() {
        RegisterRequest request = new RegisterRequest("  Viris Sok  ", "  Viris@Example.COM ", "secret123");

        User user = UserMapper.toEntity(request, "hashed");

        assertThat(user.getName()).isEqualTo("Viris Sok");
        assertThat(user.getEmail()).isEqualTo("viris@example.com");
    }

    @Test
    void storesTheGivenHashNeverTheRawPassword() {
        RegisterRequest request = new RegisterRequest("Viris", "a@example.com", "secret123");

        User user = UserMapper.toEntity(request, "hashed");

        assertThat(user.getPasswordHash()).isEqualTo("hashed").isNotEqualTo("secret123");
    }

    @Test
    void appliesFreePlanAndUserRoleDefaultsThroughTheBuilder() {
        RegisterRequest request = new RegisterRequest("Viris", "a@example.com", "secret123");

        User user = UserMapper.toEntity(request, "hashed");

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }
}
