package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.heartbeat.PingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.IntSupplier;

/**
 * Deletes history past what each plan keeps. Raw checks and pings go after
 * {@code min(plan history, raw-check-days)}; daily summaries after the plan's full history.
 *
 * <p>Raw checks are never deleted from a day that is not summarised yet: the cutoff is capped
 * at the end of the last summarised day, so a failed rollup cannot lose history.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    public record Result(long checks, long pings, long summaries) {
    }

    private final CheckRepository checkRepository;
    private final PingRepository pingRepository;
    private final CheckDailyStatRepository dailyStatRepository;
    private final PlanLimits planLimits;
    private final HousekeepingProperties properties;
    private final TransactionTemplate tx;

    public RetentionService(CheckRepository checkRepository,
                            PingRepository pingRepository,
                            CheckDailyStatRepository dailyStatRepository,
                            PlanLimits planLimits,
                            HousekeepingProperties properties,
                            PlatformTransactionManager transactionManager) {
        this.checkRepository = checkRepository;
        this.pingRepository = pingRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.planLimits = planLimits;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * @param summarisedThrough last day fully summarised; null if none is, in which case no raw
     *                          check is deleted (pings and old summaries still are).
     */
    public Result purge(LocalDate summarisedThrough, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant summarisedBefore = summarisedThrough == null ? null : DailyRollupService.startOf(summarisedThrough.plusDays(1));
        long checks = 0;
        long pings = 0;
        long summaries = 0;

        for (Plan plan : Plan.values()) {
            int historyDays = planLimits.retentionDays(plan);
            Instant rawCutoff = now.minus(Duration.ofDays(Math.min(historyDays, properties.rawCheckDays())));

            if (summarisedBefore != null) {
                Instant checkCutoff = rawCutoff.isBefore(summarisedBefore) ? rawCutoff : summarisedBefore;
                checks += inBatches(() -> checkRepository.deleteBatchForPlanBefore(plan.name(), checkCutoff, properties.batchSize()));
            }
            pings += inBatches(() -> pingRepository.deleteBatchForPlanBefore(plan.name(), rawCutoff, properties.batchSize()));
            LocalDate summaryCutoff = today.minusDays(historyDays);
            Integer deleted = tx.execute(status -> dailyStatRepository.deleteForPlanBefore(plan.name(), summaryCutoff));
            summaries += deleted == null ? 0 : deleted;
        }

        log.info("Retention deleted {} check(s), {} ping(s), {} daily summary row(s)", checks, pings, summaries);
        return new Result(checks, pings, summaries);
    }

    /** Deletes one batch per transaction until a batch comes back short. */
    private long inBatches(IntSupplier deleteBatch) {
        long total = 0;
        int deleted;
        do {
            Integer n = tx.execute(status -> deleteBatch.getAsInt());
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted == properties.batchSize());
        return total;
    }
}
