package com.viris.PulseGuard.auth.jwt;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.enumeration.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi0xMjM0NTY3OA==";
    private static final String OTHER_SECRET = "b3RoZXItc2VjcmV0LXRoYXQtaXMtYWxzby0yNTYtYml0cy1sb25nLTEyMzQ1Ng==";

    private static JwtService service(String secret, String issuer, String audience, Duration accessTtl) {
        return service(secret, null, issuer, audience, accessTtl);
    }

    private static JwtService service(String secret, String previousSecret, String issuer,
                                      String audience, Duration accessTtl) {
        return new JwtService(new JwtProperties(secret, previousSecret, issuer, audience,
                accessTtl, Duration.ofDays(30), Duration.ZERO));
    }

    private final JwtService jwtService = service(SECRET, "pulseguard", "pulseguard-api", Duration.ofMinutes(15));

    private UserPrincipal principal() {
        return UserPrincipal.from(User.builder()
                .id(42L)
                .name("Viris")
                .email("a@example.com")
                .passwordHash("irrelevant-hash")
                .role(Role.ADMIN)
                .build());
    }

    @Test
    void roundTripsIdentity() {
        ParsedToken parsed = jwtService.parse(jwtService.generateAccessToken(principal()));

        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("a@example.com");
        assertThat(parsed.type()).isEqualTo(TokenType.ACCESS);
        assertThat(parsed.jti()).isNotBlank();
    }

    @Test
    void marksRefreshTokensDistinctly() {
        assertThat(jwtService.parse(jwtService.generateRefreshToken(principal())).type())
                .isEqualTo(TokenType.REFRESH);
    }

    @Test
    void givesEveryTokenItsOwnId() {
        String first = jwtService.parse(jwtService.generateAccessToken(principal())).jti();
        String second = jwtService.parse(jwtService.generateAccessToken(principal())).jti();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void carriesNoAuthorityOrPasswordHash() {
        String token = jwtService.generateAccessToken(principal());

        // Authority is deliberately absent: it is read from the database per request.
        assertThat(token).doesNotContain("irrelevant-hash");
        assertThat(jwtService.parse(token)).hasNoNullFieldsOrProperties();
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String foreign = service(OTHER_SECRET, "pulseguard", "pulseguard-api", Duration.ofMinutes(15))
                .generateAccessToken(principal());

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        String foreign = service(SECRET, "someone-else", "pulseguard-api", Duration.ofMinutes(15))
                .generateAccessToken(principal());

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenForAnotherAudience() {
        String foreign = service(SECRET, "pulseguard", "some-other-api", Duration.ofMinutes(15))
                .generateAccessToken(principal());

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = service(SECRET, "pulseguard", "pulseguard-api", Duration.ofSeconds(-60))
                .generateAccessToken(principal());

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void verifiesATokenSignedWithThePreviousKeyDuringRotation() {
        String oldToken = service(OTHER_SECRET, "pulseguard", "pulseguard-api", Duration.ofMinutes(15))
                .generateAccessToken(principal());
        JwtService rotated = service(SECRET, OTHER_SECRET, "pulseguard", "pulseguard-api", Duration.ofMinutes(15));

        assertThat(rotated.parse(oldToken).userId()).isEqualTo(42L);
    }

    @Test
    void stillSignsWithTheCurrentKeyDuringRotation() {
        JwtService rotated = service(SECRET, OTHER_SECRET, "pulseguard", "pulseguard-api", Duration.ofMinutes(15));

        // A token it issues must verify against the current key alone, so the old one can be dropped.
        assertThat(jwtService.parse(rotated.generateAccessToken(principal())).userId()).isEqualTo(42L);
    }

    @Test
    void exposesIssuedAtForSessionCutoffs() {
        assertThat(jwtService.parse(jwtService.generateAccessToken(principal())).issuedAt())
                .isBeforeOrEqualTo(java.time.Instant.now());
    }

    @Test
    void rejectsAGarbageToken() {
        assertThatThrownBy(() -> jwtService.parse("not.a.jwt"))
                .isInstanceOfAny(JwtException.class, IllegalArgumentException.class);
    }
}
