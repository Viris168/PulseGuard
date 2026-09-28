package com.viris.PulseGuard.heartbeat;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * One job for all heartbeats, on the clustered Quartz store: one node sweeps at a time, and
 * {@code @DisallowConcurrentExecution} stops a slow sweep overlapping the next. Never throws,
 * for the same reason as CheckJob: the next fire is the retry.
 */
@DisallowConcurrentExecution
public class HeartbeatSweepJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSweepJob.class);

    private final HeartbeatSweeper sweeper;

    public HeartbeatSweepJob(HeartbeatSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            int missed = sweeper.sweep(Instant.now());
            if (missed > 0) {
                log.info("Heartbeat sweep marked {} monitor(s) missed", missed);
            }
        } catch (Exception e) {
            log.error("Heartbeat sweep failed", e);
        }
    }
}
