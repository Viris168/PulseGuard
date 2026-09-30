package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.AiAccessDto;
import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.ai.dto.AskAiRequest;
import com.viris.PulseGuard.ai.dto.AskAiResponse;
import com.viris.PulseGuard.auth.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask AI. Session only (SecurityConfig): every question costs money, and consent is a choice
 * the person makes, not something an API key should be able to change.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AskAiController {

    private final AskAiService askAiService;

    @GetMapping("/access")
    public AiAccessDto access(@AuthenticationPrincipal UserPrincipal principal) {
        return askAiService.access(principal.getUserId());
    }

    @PutMapping("/access")
    public AiAccessDto saveAccess(@AuthenticationPrincipal UserPrincipal principal,
                                  @Valid @RequestBody AiAccessDto request) {
        return askAiService.saveAccess(principal.getUserId(), request);
    }

    @GetMapping("/quota")
    public AiQuotaResponse quota(@AuthenticationPrincipal UserPrincipal principal) {
        return askAiService.quota(principal.getUserId());
    }

    @PostMapping("/ask")
    public AskAiResponse ask(@AuthenticationPrincipal UserPrincipal principal,
                             @Valid @RequestBody AskAiRequest request) {
        return askAiService.ask(principal.getUserId(), request.question(), request.timeZone());
    }
}
