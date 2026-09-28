package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.incident.IncidentEngine;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Finds heartbeats whose deadline has passed and marks each missed, one transaction per
 * monitor so one failure never holds up the rest. Run by {@link HeartbeatSweepJob}.
 */
@Service
public class HeartbeatSweeper {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSweeper.class);

    /** Per sweep. More overdue monitors than this are simply picked up by the next sweep. */
    static final int BATCH = 500;

    private final MonitorRepository monitorRepository;
    private final IncidentEngine incidentEngine;

    public HeartbeatSweeper(MonitorRepository monitorRepository, IncidentEngine incidentEngine) {
        this.monitorRepository = monitorRepository;
        this.incidentEngine = incidentEngine;
    }

    /** @return how many monitors were handled */
    public int sweep(Instant now) {
        List<Long> overdue = monitorRepository.findOverdueHeartbeatIds(now, PageRequest.of(0, BATCH));
        int handled = 0;
        for (Long monitorId : overdue) {
            try {
                incidentEngine.heartbeatMissed(monitorId, now);
                handled++;
            } catch (OptimisticLockingFailureException e) {
                // Most likely a ping just arrived: it moved the deadline, so there is no miss.
                // If there still is one, the next sweep finds it.
                log.info("Concurrent update on monitorId={}; leaving it to the next sweep", monitorId);
            } catch (RuntimeException e) {
                log.error("Heartbeat sweep failed for monitorId={}", monitorId, e);
            }
        }
        return handled;
    }
}
