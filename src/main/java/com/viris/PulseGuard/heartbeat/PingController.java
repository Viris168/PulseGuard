package com.viris.PulseGuard.heartbeat;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The URL a heartbeat's job calls: open to anyone holding the token (SecurityConfig), and
 * answered in plain text because it is read by curl in a cron log, not by the app. No
 * {@code produces} on purpose: it would stop error responses rendering as JSON.
 */
@RestController
@RequiredArgsConstructor
public class PingController {

    private final HeartbeatService heartbeatService;

    @RequestMapping(value = "/api/ping/{token}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.HEAD})
    public ResponseEntity<String> ping(@PathVariable String token, HttpServletRequest request) {
        HeartbeatService.Outcome outcome = heartbeatService.receive(token, request.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN)
                .body(outcome == HeartbeatService.Outcome.PAUSED ? "OK (monitor paused, ping ignored)\n" : "OK\n");
    }
}
