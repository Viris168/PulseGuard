package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.dto.AuthResponse;
import com.viris.PulseGuard.auth.dto.ChangePasswordRequest;
import com.viris.PulseGuard.auth.dto.LoginRequest;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.jwt.JwtProperties;
import com.viris.PulseGuard.auth.jwt.JwtService;
import com.viris.PulseGuard.auth.jwt.TokenType;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.common.exception.EmailAlreadyUsedException;
import com.viris.PulseGuard.common.exception.InvalidCredentialsException;
import com.viris.PulseGuard.common.exception.PasswordUnchangedException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String IP = "10.0.0.1";

    @Mock
    UserRepository users;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    JwtService jwtService;
    InMemoryTokenDenylist denylist;
    AuthService authService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new JwtProperties(
                "dGVzdC1vbmx5LXNpZ25pbmcta2V5LW5vdC11c2VkLWFueXdoZXJlLWVsc2U=", null,
                "pulseguard-test", "pulseguard-api-test",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(30)));
        denylist = new InMemoryTokenDenylist();

        // Real DaoAuthenticationProvider over a mocked repository, so the test exercises the
        // same authentication path the application uses.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new AppUserDetailsService(users));
        provider.setPasswordEncoder(passwordEncoder);

        authService = new AuthService(users, passwordEncoder, new ProviderManager(provider),
                jwtService, denylist, new InMemoryLoginRateLimiter(5, 20));
    }

    private User storedUser() {
        return User.builder()
                .id(1L)
                .name("Viris")
                .email("a@example.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .build();
    }

    // --- register ---------------------------------------------------------

    @Test
    void registerHashesThePassword() {
        when(users.existsByEmail("a@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        authService.register(new RegisterRequest("Viris", "a@example.com", "secret123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(passwordEncoder.matches("secret123", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void registerChecksExistingEmailInNormalizedForm() {
        when(users.existsByEmail("a@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Viris", "A@Example.com", "secret123")))
                .isInstanceOf(EmailAlreadyUsedException.class);
        verify(users, never()).save(any());
    }

    @Test
    void registerReturnsBothTokensAndTheUser() {
        when(users.existsByEmail("a@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.register(new RegisterRequest("Viris", "a@example.com", "secret123"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(response.user().name()).isEqualTo("Viris");
    }

    // --- login ------------------------------------------------------------

    @Test
    void loginAcceptsADifferentlyCasedEmail() {
        User stored = storedUser();
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(stored));
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        AuthResponse response = authService.login(new LoginRequest("A@Example.COM", "secret123"), IP);

        assertThat(jwtService.parse(response.token()).userId()).isEqualTo(1L);
    }

    @Test
    void loginRejectsAWrongPassword() {
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(storedUser()));

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "wrong"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsAnUnknownEmail() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "secret123"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsADisabledAccount() {
        User disabled = storedUser();
        disabled.setEnabled(false);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "secret123"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsALockedAccount() {
        User locked = storedUser();
        locked.setLocked(true);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "secret123"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginIsRateLimitedAfterRepeatedFailures() {
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(storedUser()));
        LoginRequest wrong = new LoginRequest("a@example.com", "wrong");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(wrong, IP)).isInstanceOf(InvalidCredentialsException.class);
        }

        // The sixth attempt never reaches the password check.
        assertThatThrownBy(() -> authService.login(wrong, IP)).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void successfulLoginClearsTheAttemptCounter() {
        User stored = storedUser();
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(stored));
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "wrong"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        authService.login(new LoginRequest("a@example.com", "secret123"), IP);

        // Counter was reset, so failures start over rather than tripping the limit immediately.
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "wrong"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // --- refresh / logout -------------------------------------------------

    @Test
    void refreshIssuesANewPairAndRevokesTheOldRefreshToken() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        String refreshToken = jwtService.generateRefreshToken(UserPrincipal.from(stored));

        AuthResponse response = authService.refresh(refreshToken, null);

        assertThat(response.token()).isNotBlank();
        assertThat(denylist.isRevoked(jwtService.parse(refreshToken).jti())).isTrue();
        assertThatThrownBy(() -> authService.refresh(refreshToken, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshRevokesTheAccessTokenSentAlongsideIt() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        UserPrincipal principal = UserPrincipal.from(stored);
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = jwtService.generateRefreshToken(principal);

        authService.refresh(refreshToken, accessToken);

        assertThat(denylist.isRevoked(jwtService.parse(accessToken).jti())).isTrue();
    }

    @Test
    void refreshLeavesAnotherUsersAccessTokenAlone() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        String refreshToken = jwtService.generateRefreshToken(UserPrincipal.from(stored));

        User other = User.builder().id(2L).name("Other").email("b@example.com")
                .passwordHash(passwordEncoder.encode("secret123")).build();
        String othersAccessToken = jwtService.generateAccessToken(UserPrincipal.from(other));

        authService.refresh(refreshToken, othersAccessToken);

        assertThat(denylist.isRevoked(jwtService.parse(othersAccessToken).jti())).isFalse();
    }

    @Test
    void refreshStillSucceedsWhenTheAccessTokenIsUnusable() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        String refreshToken = jwtService.generateRefreshToken(UserPrincipal.from(stored));

        assertThat(authService.refresh(refreshToken, "not-a-token").token()).isNotBlank();
    }

    @Test
    void refreshRejectsAnAccessToken() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        String accessToken = jwtService.generateAccessToken(UserPrincipal.from(stored));

        assertThatThrownBy(() -> authService.refresh(accessToken, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshRejectsADisabledAccount() {
        User stored = storedUser();
        String refreshToken = jwtService.generateRefreshToken(UserPrincipal.from(stored));
        stored.setEnabled(false);
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(refreshToken, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logoutEndsEverySessionNotJustThePresentedToken() throws Exception {
        User stored = storedUser();
        UserPrincipal principal = UserPrincipal.from(stored);
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = jwtService.generateRefreshToken(principal);
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        Thread.sleep(1100); // iat has second precision; make the cut-off strictly later.
        authService.logout(accessToken);

        assertThat(denylist.isRevoked(jwtService.parse(accessToken).jti())).isTrue();
        // The refresh token was never handed back, yet it is dead too.
        assertThatThrownBy(() -> authService.refresh(refreshToken, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logoutIgnoresAnUnreadableToken() {
        authService.logout("not.a.jwt");
        // No exception: logout is idempotent.
    }

    @Test
    void refreshTokenTypeIsDistinctFromAccess() {
        User stored = storedUser();
        assertThat(jwtService.parse(jwtService.generateRefreshToken(UserPrincipal.from(stored))).type())
                .isEqualTo(TokenType.REFRESH);
    }

    // --- change password --------------------------------------------------

    @Test
    void changePasswordStoresANewHash() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("secret123", "brand-new-password"));

        assertThat(passwordEncoder.matches("brand-new-password", stored.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("secret123", stored.getPasswordHash())).isFalse();
    }

    @Test
    void changePasswordRejectsAWrongCurrentPassword() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("wrong", "brand-new-password")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordRejectsReusingTheSamePassword() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("secret123", "secret123")))
                .isInstanceOf(PasswordUnchangedException.class);
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordRevokesEveryExistingSession() throws Exception {
        User stored = storedUser();
        String oldRefreshToken = jwtService.generateRefreshToken(UserPrincipal.from(stored));
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        Thread.sleep(1100); // iat has second precision; make the cut-off strictly later.
        authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("secret123", "brand-new-password"));

        assertThat(denylist.isRevokedForUser(1L, jwtService.parse(oldRefreshToken).issuedAt())).isTrue();
        assertThatThrownBy(() -> authService.refresh(oldRefreshToken, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changePasswordKeepsTheCallerSignedInWithFreshTokens() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("secret123", "brand-new-password"));

        var issued = jwtService.parse(response.token());
        assertThat(issued.userId()).isEqualTo(1L);
        assertThat(denylist.isRevokedForUser(1L, issued.issuedAt())).isFalse();
    }

    @Test
    void loginWorksWithTheNewPasswordOnly() {
        User stored = storedUser();
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(stored));

        authService.changePassword(UserPrincipal.from(stored),
                new ChangePasswordRequest("secret123", "brand-new-password"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@example.com", "secret123"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(authService.login(new LoginRequest("a@example.com", "brand-new-password"), IP).token())
                .isNotBlank();
    }
}
