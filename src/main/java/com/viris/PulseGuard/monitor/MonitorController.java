package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.monitor.dto.MonitorRequest;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

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
    public List<MonitorResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
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
