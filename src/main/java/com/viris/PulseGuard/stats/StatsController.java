package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
public class StatsController {

    private final MonitorStatsService statsService;

    /** {@code ?range=24h|7d|30d}; longer ranges than the plan's history are a 403. */
    @GetMapping("/{id}/stats")
    public MonitorRangeStats stats(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable Long id,
                                   @RequestParam(defaultValue = "24h") StatsRange range) {
        return statsService.stats(principal.getUserId(), id, range);
    }
}
