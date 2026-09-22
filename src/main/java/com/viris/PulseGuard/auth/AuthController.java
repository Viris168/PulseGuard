package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.auth.dto.AuthResponse;
import com.viris.PulseGuard.auth.dto.ChangePasswordRequest;
import com.viris.PulseGuard.auth.dto.LoginRequest;
import com.viris.PulseGuard.auth.dto.RefreshRequest;
import com.viris.PulseGuard.auth.dto.RegisterRequest;
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

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal);
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
