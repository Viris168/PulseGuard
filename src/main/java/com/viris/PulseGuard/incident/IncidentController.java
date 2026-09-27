package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import com.viris.PulseGuard.incident.dto.IncidentResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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
}
