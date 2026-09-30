package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.ai.IncidentSummaryService;
import com.viris.PulseGuard.ai.dto.IncidentSummaryResponse;
import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import com.viris.PulseGuard.incident.dto.IncidentResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The caller's incidents. The user id comes from the token, never from the request. */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentQueryService incidentQueryService;
    private final IncidentSummaryService incidentSummaryService;

    @GetMapping
    public List<IncidentResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                       @RequestParam(required = false) IncidentStatus status,
                                       @RequestParam(required = false) Long monitorId,
                                       @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return incidentQueryService.list(principal.getUserId(), status, monitorId, limit);
    }

    @GetMapping("/{id}")
    public IncidentDetailResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return incidentQueryService.detail(principal.getUserId(), id);
    }

    /**
     * The AI-written summary, or 204 when AI is off or the provider failed: the frontend then
     * shows its own rule-based summary. Session only (SecurityConfig): each call can cost money.
     */
    @GetMapping("/{id}/summary")
    public ResponseEntity<IncidentSummaryResponse> summary(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id) {
        return incidentSummaryService.summarize(principal.getUserId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
