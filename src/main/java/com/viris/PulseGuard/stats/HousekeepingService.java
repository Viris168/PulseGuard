package com.viris.PulseGuard.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** One nightly pass: summarise first, then delete. Never the other way round. */
@Service
public class HousekeepingService {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingService.class);

    private final DailyRollupService rollupService;
    private final RetentionService retentionService;
    private final CheckDailyStatRepository dailyStatRepository;

    public HousekeepingService(DailyRollupService rollupService,
                               RetentionService retentionService,
                               CheckDailyStatRepository dailyStatRepository) {
        this.rollupService = rollupService;
        this.retentionService = retentionService;
        this.dailyStatRepository = dailyStatRepository;
    }

    public RetentionService.Result run(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate summarisedThrough;
        try {
            summarisedThrough = rollupService.rollUp(today);
        } catch (RuntimeException e) {
            // Still trim what is safe: raw checks only up to the last day that did get summarised.
            summarisedThrough = dailyStatRepository.latestDay();
            log.error("Daily rollup failed; retention limited to days summarised through {}", summarisedThrough, e);
        }
        return retentionService.purge(summarisedThrough, now);
    }
}
