package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.check.dto.CheckResponse;
import com.viris.PulseGuard.monitor.dto.MonitorRequest;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import com.viris.PulseGuard.monitor.dto.MonitorSummaryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;

/**
 * Monitor CRUD. Every route is authenticated (SecurityConfig's {@code anyRequest()}), and
 * the caller's id comes from the token — never from the path or body — so one tenant cannot
 * name another's. A monitor belonging to someone else is a 404, not a 403.
 */
@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @GetMapping
    public List<MonitorSummaryResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return monitorService.listMonitors(principal.getUserId());
    }

    @PostMapping
    public ResponseEntity<MonitorResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody MonitorRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        MonitorResponse created = monitorService.createMonitor(principal.getUserId(), request);
        return ResponseEntity
                .created(uriBuilder.path("/api/monitors/{id}").build(created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public MonitorResponse get(@AuthenticationPrincipal UserPrincipal principal,
                               @PathVariable Long id) {
        return monitorService.getMonitor(principal.getUserId(), id);
    }

    /**
     * Check history. {@code from}/{@code to} are optional ISO-8601 bounds; {@code order} is
     * asc or desc (newest first by default). Capped at 100: an unbounded limit would let one
     * request pull millions of rows into memory.
     */
    @GetMapping("/{id}/checks")
    public List<CheckResponse> checks(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable Long id,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                      @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String order) {
        return monitorService.checkHistory(principal.getUserId(), id, from, to, limit,
                "asc".equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC);
    }

    @PutMapping("/{id}")
    public MonitorResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable Long id,
                                  @Valid @RequestBody MonitorRequest request) {
        return monitorService.updateMonitor(principal.getUserId(), id, request);
    }

    @PostMapping("/{id}/pause")
    public MonitorResponse pause(@AuthenticationPrincipal UserPrincipal principal,
                                 @PathVariable Long id) {
        return monitorService.pauseMonitor(principal.getUserId(), id);
    }

    @PostMapping("/{id}/resume")
    public MonitorResponse resume(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable Long id) {
        return monitorService.resumeMonitor(principal.getUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal,
                       @PathVariable Long id) {
        monitorService.deleteMonitor(principal.getUserId(), id);
    }
}
