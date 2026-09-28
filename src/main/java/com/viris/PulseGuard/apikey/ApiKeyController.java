package com.viris.PulseGuard.apikey;

import com.viris.PulseGuard.apikey.dto.ApiKeyRequest;
import com.viris.PulseGuard.apikey.dto.ApiKeyResponse;
import com.viris.PulseGuard.apikey.dto.CreatedApiKeyResponse;
import com.viris.PulseGuard.auth.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Managing keys needs a signed-in session: SecurityConfig refuses API keys here, so a leaked
 * key cannot mint more keys or list the others.
 */
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return apiKeyService.listKeys(principal.getUserId());
    }

    /** 201 with the key itself, the only time it is ever returned. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                        @Valid @RequestBody ApiKeyRequest request) {
        return apiKeyService.createKey(principal.getUserId(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        apiKeyService.revokeKey(principal.getUserId(), id);
    }
}
