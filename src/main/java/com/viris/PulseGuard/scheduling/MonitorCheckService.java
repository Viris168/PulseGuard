package com.viris.PulseGuard.scheduling;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckExecutor;
import com.viris.PulseGuard.incident.IncidentEngine;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * What one scheduled fire does for one monitor. Deliberately not {@code @Transactional}:
 * no database transaction may stay open across the HTTP call, which can take the monitor's
 * whole timeout. The executor saves its own row, and the timestamp update is its own write.
 *
 * <p>This is also the seam for later steps: the incident engine consumes the result here,
 * and a Redis queue can replace the direct executor call without touching {@link CheckJob}.
 */
@Service
public class MonitorCheckService {

    private static final Logger log = LoggerFactory.getLogger(MonitorCheckService.class);

    private final MonitorRepository monitorRepository;
    private final CheckExecutor checkExecutor;
    private final SchedulerService schedulerService;
    private final MeterRegistry meterRegistry;
    private final IncidentEngine incidentEngine;

    public MonitorCheckService(MonitorRepository monitorRepository,
                               CheckExecutor checkExecutor,
                               SchedulerService schedulerService,
                               MeterRegistry meterRegistry,
                               IncidentEngine incidentEngine) {
        this.monitorRepository = monitorRepository;
        this.checkExecutor = checkExecutor;
        this.schedulerService = schedulerService;
        this.meterRegistry = meterRegistry;
        this.incidentEngine = incidentEngine;
    }

    public void runCheck(Long monitorId) {
        // Internal path: the job only knows the id, and it was written by our own scheduler.
        Optional<Monitor> found = monitorRepository.findById(monitorId);
        if (found.isEmpty()) {
            // E.g. the owner's account was deleted and the monitor went with it by cascade,
            // which no service call saw. The job cleans itself up.
            log.warn("Monitor gone; unscheduling orphaned job for monitorId={}", monitorId);
            schedulerService.unschedule(monitorId);
            return;
        }
        Monitor monitor = found.get();
        if (!monitor.isActive()) {
            // Pausing removes the job, so this only happens if the two drifted apart.
            log.warn("Monitor paused; unscheduling stray job for monitorId={}", monitorId);
            schedulerService.unschedule(monitorId);
            return;
        }

        Check check = checkExecutor.execute(monitor);
        // Tagged by result only: a monitorId tag would create one time series per monitor.
        Counter.builder("pulseguard.checks")
                .tag("result", check.getResult().name())
                .register(meterRegistry)
                .increment();
        monitorRepository.touchLastCheckedAt(monitorId, check.getCheckedAt());
        // Its own transaction: state, counters and any incident change commit together.
        evaluateWithRetry(monitorId, check);
    }

    private static final int MAX_EVALUATE_ATTEMPTS = 3;

    private void evaluateWithRetry(Long monitorId, Check check) {
        for (int attempt = 1; ; attempt++) {
            try {
                incidentEngine.evaluate(monitorId, check);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_EVALUATE_ATTEMPTS) {
                    throw e;   // CheckJob logs it; the next scheduled check carries on
                }
                log.info("Concurrent update on monitorId={}, retrying evaluation (attempt {})",
                        monitorId, attempt + 1);
            }
        }
    }
}
