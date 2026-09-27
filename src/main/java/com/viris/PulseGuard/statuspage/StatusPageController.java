package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.statuspage.dto.StatusPageRequest;
import com.viris.PulseGuard.statuspage.dto.StatusPageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own status page. One per account, so there is no id in the path. */
@RestController
@RequestMapping("/api/status-page")
@RequiredArgsConstructor
public class StatusPageController {

    private final StatusPageService statusPageService;

    /** 204 until the page is first saved. */
    @GetMapping
    public ResponseEntity<StatusPageResponse> get(@AuthenticationPrincipal UserPrincipal principal) {
        return statusPageService.getStatusPage(principal.getUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Creates or replaces the page. */
    @PutMapping
    public StatusPageResponse save(@AuthenticationPrincipal UserPrincipal principal,
                                   @Valid @RequestBody StatusPageRequest request) {
        return statusPageService.saveStatusPage(principal.getUserId(), request);
    }
}
