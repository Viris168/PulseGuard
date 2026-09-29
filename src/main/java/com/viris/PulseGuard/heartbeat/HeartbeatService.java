package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.common.exception.HeartbeatNotFoundException;
import com.viris.PulseGuard.common.exception.InvalidMonitorException;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.common.exception.MonitorPausedException;
import com.viris.PulseGuard.heartbeat.dto.PingResponse;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.incident.IncidentEngine;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Receiving pings, from the job (by token) or from the owner's "Send test ping", and reading
 * them back. A ping is saved and applied to the monitor in one transaction; if the sweeper
 * changed the monitor at the same moment, @Version rejects one of them and the ping retries.
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_IP_LENGTH = 45;

    /**
     * Pings closer together than this, while the monitor is up, are answered but not stored: a
     * job stuck in a loop (or retrying too eagerly) would otherwise grow the pings table without
     * bound. Heartbeat periods are a minute at least, so no real schedule is affected.
     */
    static final Duration MIN_SPACING = Duration.ofSeconds(10);

    /** DUPLICATE: accepted, but too soon after the previous ping to be worth storing. */
    public enum Outcome { RECORDED, PAUSED, DUPLICATE }

    private final MonitorRepository monitorRepository;
    private final PingRepository pingRepository;
    private final IncidentEngine incidentEngine;
    private final TransactionTemplate tx;

    public HeartbeatService(MonitorRepository monitorRepository,
                            PingRepository pingRepository,
                            IncidentEngine incidentEngine,
                            PlatformTransactionManager transactionManager) {
        this.monitorRepository = monitorRepository;
        this.pingRepository = pingRepository;
        this.incidentEngine = incidentEngine;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * The public ping. A paused monitor answers OK but records nothing: the job's own run
     * must not fail because monitoring is paused, and a ping must not quietly un-pause it.
     */
    public Outcome receive(String token, String sourceIp) {
        if (!HeartbeatSchedule.looksLikeToken(token)) {
            throw new HeartbeatNotFoundException();
        }
        return withRetry(() -> {
            Monitor monitor = monitorRepository.findByHeartbeatToken(token)
                    .orElseThrow(HeartbeatNotFoundException::new);
            if (!monitor.isActive()) {
                log.debug("Ping ignored for paused monitorId={}", monitor.getId());
                return Outcome.PAUSED;
            }
            if (isDuplicate(monitor, Instant.now())) {
                return Outcome.DUPLICATE;
            }
            record(monitor, sourceIp);
            return Outcome.RECORDED;
        });
    }

    /** Too soon after the last ping, with nothing to change: an outage always ends at the next ping. */
    private static boolean isDuplicate(Monitor monitor, Instant now) {
        Instant previous = monitor.getLastCheckedAt();
        return previous != null && monitor.getState() == MonitorState.UP
                && now.isBefore(previous.plus(MIN_SPACING));
    }

    /** "Send test ping" from the dashboard: a real ping, from the owner's own address. */
    public void testPing(Long userId, Long monitorId, String sourceIp) {
        withRetry(() -> {
            Monitor monitor = monitorRepository.findByIdAndUserId(monitorId, userId)
                    .orElseThrow(() -> new MonitorNotFoundException(monitorId));
            if (!monitor.isHeartbeat()) {
                throw new InvalidMonitorException("type", "Only heartbeat monitors receive pings");
            }
            if (!monitor.isActive()) {
                throw new MonitorPausedException();
            }
            record(monitor, sourceIp);
            return Outcome.RECORDED;
        });
    }

    /** Ping history, owner-checked first: the pings query itself is keyed by monitor id alone. */
    @Transactional(readOnly = true)
    public List<PingResponse> listPings(Long userId, Long monitorId, Instant from, Instant to,
                                        int limit, Sort.Direction direction) {
        monitorRepository.findByIdAndUserId(monitorId, userId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        return pingRepository.findByMonitorIdAndReceivedAtBetween(monitorId,
                        from != null ? from : Instant.EPOCH, to != null ? to : Instant.now(),
                        PageRequest.of(0, limit, Sort.by(direction, "receivedAt"))).stream()
                .map(PingResponse::from)
                .toList();
    }

    private void record(Monitor monitor, String sourceIp) {
        Instant now = Instant.now();
        Ping ping = new Ping();
        ping.setMonitor(monitor);
        ping.setReceivedAt(now);
        ping.setSourceIp(sourceIp == null ? "unknown"
                : sourceIp.length() > MAX_IP_LENGTH ? sourceIp.substring(0, MAX_IP_LENGTH) : sourceIp);
        pingRepository.save(ping);
        incidentEngine.heartbeatReceived(monitor.getId(), now);
        log.debug("Ping recorded for monitorId={}", monitor.getId());
    }

    private <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Concurrent update while recording a ping, retrying (attempt {})", attempt + 1);
            }
        }
    }
}
