package com.viris.PulseGuard.stats;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * The nightly run, on the clustered Quartz store: one node runs it however many there are.
 * Never throws, like the other jobs: tomorrow's run is the retry.
 */
@DisallowConcurrentExecution
public class HousekeepingJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

    private final HousekeepingService housekeeping;

    public HousekeepingJob(HousekeepingService housekeeping) {
        this.housekeeping = housekeeping;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            housekeeping.run(Instant.now());
        } catch (Exception e) {
            log.error("Housekeeping run failed", e);
        }
    }
}
