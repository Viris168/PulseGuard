package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.heartbeat.dto.PingResponse;
import com.viris.PulseGuard.monitor.MonitorService;
import com.viris.PulseGuard.monitor.dto.MonitorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The owner's side of a heartbeat: its ping history and the test ping. Tenant-scoped like MonitorController. */
@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
public class HeartbeatController {

    private final HeartbeatService heartbeatService;
    private final MonitorService monitorService;

    /** Same query parameters as /checks: optional ISO bounds, limit up to 100, newest first by default. */
    @GetMapping("/{id}/pings")
    public List<PingResponse> pings(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                    @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String order) {
        return heartbeatService.listPings(principal.getUserId(), id, from, to, limit,
                "asc".equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC);
    }

    /** Records a ping as if the job had sent it; returns the monitor as it is now. */
    @PostMapping("/{id}/test-ping")
    public MonitorResponse testPing(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id,
                                    HttpServletRequest request) {
        heartbeatService.testPing(principal.getUserId(), id, request.getRemoteAddr());
        return monitorService.getMonitor(principal.getUserId(), id);
    }
}
