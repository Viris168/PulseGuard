package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.dto.AuthResponse;
import com.viris.PulseGuard.auth.account.AccountDeletionService;
import com.viris.PulseGuard.auth.account.EmailChangeService;
import com.viris.PulseGuard.auth.account.EmailVerificationService;
import com.viris.PulseGuard.auth.dto.ChangeEmailRequest;
import com.viris.PulseGuard.auth.dto.ChangePasswordRequest;
import com.viris.PulseGuard.auth.dto.DeleteAccountRequest;
import com.viris.PulseGuard.auth.dto.TokenRequest;
import com.viris.PulseGuard.auth.dto.ForgotPasswordRequest;
import com.viris.PulseGuard.auth.dto.ResetPasswordRequest;
import com.viris.PulseGuard.auth.reset.PasswordResetService;
import com.viris.PulseGuard.auth.dto.LoginRequest;
import com.viris.PulseGuard.auth.dto.RefreshRequest;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
import com.viris.PulseGuard.auth.dto.UpdateProfileRequest;
import com.viris.PulseGuard.auth.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final EmailChangeService emailChangeService;
    private final AccountDeletionService accountDeletionService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        // Direct socket address. Behind a proxy, configure server.forward-headers-strategy so
        // this reflects X-Forwarded-For rather than the proxy itself.
        return authService.login(request, servletRequest.getRemoteAddr());
    }

    /**
     * Send the current access token in {@code Authorization} as well, and it is retired with the
     * refresh token it came with. Optional: without it the old access token simply runs out its
     * remaining lifetime.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request,
                                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                String authorization) {
        return authService.refresh(request.refreshToken(), bearerToken(authorization));
    }

    /** Ends every session for the caller, so an unreturned refresh token cannot outlive it. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        authService.logout(authorization.substring(BEARER_PREFIX.length()).trim());
    }

    /** Returns a fresh token pair: every other session is revoked, this one continues. */
    @PostMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(principal, request);
    }

    /** 202 whether or not the address has an account; the email, if any, follows. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest servletRequest) {
        passwordResetService.requestReset(request.email(), servletRequest.getRemoteAddr());
    }

    /** 204, then the user signs in with the new password; every old session is revoked. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
    }

    /** From the link emailed at sign-up; works signed in or not. */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        emailVerificationService.verify(request.token());
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest servletRequest) {
        emailVerificationService.resend(principal.getUserId(), servletRequest.getRemoteAddr());
    }

    /** Sends a confirmation link to the new address; nothing changes until it is used. */
    @PostMapping("/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void changeEmail(@AuthenticationPrincipal UserPrincipal principal,
                            @Valid @RequestBody ChangeEmailRequest request,
                            HttpServletRequest servletRequest) {
        emailChangeService.requestChange(principal.getUserId(), request.newEmail(), request.currentPassword(),
                servletRequest.getRemoteAddr());
    }

    /** From the link sent to the new address; works signed in or not. */
    @PostMapping("/confirm-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmail(@Valid @RequestBody TokenRequest request) {
        emailChangeService.confirm(request.token());
    }

    /** Cancels any subscription, then deletes the account and everything it owns. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal UserPrincipal principal,
                              @Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.deleteAccount(principal.getUserId(), request.currentPassword());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal);
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(principal, request);
    }

    /** @return the bearer token, or {@code null} when the header is absent or unusable. */
    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
