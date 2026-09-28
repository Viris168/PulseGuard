package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.check.CheckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Summarises raw checks into {@code check_daily_stats}, one row per monitor per UTC day, so
 * history outlives the raw checks retention deletes. Days are UTC, like the status page's bars.
 */
@Service
public class DailyRollupService {

    private static final Logger log = LoggerFactory.getLogger(DailyRollupService.class);

    /** The longest history any plan keeps; older raw checks (only on a first run) are not worth summarising. */
    static final int MAX_BACKFILL_DAYS = 366;

    private final CheckRepository checkRepository;
    private final CheckDailyStatRepository dailyStatRepository;
    private final TransactionTemplate tx;

    public DailyRollupService(CheckRepository checkRepository,
                              CheckDailyStatRepository dailyStatRepository,
                              PlatformTransactionManager transactionManager) {
        this.checkRepository = checkRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Summarises every day not yet summarised, up to yesterday. Yesterday is always redone,
     * which picks up checks that finished after last night's run. The first run backfills from
     * the oldest raw check. Each day is its own transaction, so a failure keeps the days before it.
     *
     * @return the last day now summarised: yesterday.
     */
    public LocalDate rollUp(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate start = firstDayToSummarise();
        if (start == null) {
            return yesterday; // no checks at all: every day up to yesterday is trivially summarised
        }
        if (start.isAfter(yesterday)) {
            start = yesterday;
        }
        LocalDate earliest = today.minusDays(MAX_BACKFILL_DAYS);
        if (start.isBefore(earliest)) {
            start = earliest;
        }

        int days = 0;
        long rows = 0;
        for (LocalDate day = start; !day.isAfter(yesterday); day = day.plusDays(1)) {
            LocalDate d = day;
            Integer written = tx.execute(status -> dailyStatRepository.rollUpDay(d, startOf(d), startOf(d.plusDays(1))));
            rows += written == null ? 0 : written;
            days++;
        }
        log.info("Rolled up {} day(s) from {} to {}: {} monitor-day row(s)", days, start, yesterday, rows);
        return yesterday;
    }

    private LocalDate firstDayToSummarise() {
        LocalDate latest = dailyStatRepository.latestDay();
        if (latest != null) {
            return latest.plusDays(1);
        }
        Instant oldest = checkRepository.oldestCheckedAt();
        return oldest == null ? null : oldest.atZone(ZoneOffset.UTC).toLocalDate();
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
