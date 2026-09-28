package com.viris.PulseGuard.stats;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HousekeepingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T03:15:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private final DailyRollupService rollup = mock(DailyRollupService.class);
    private final RetentionService retention = mock(RetentionService.class);
    private final CheckDailyStatRepository dailyStats = mock(CheckDailyStatRepository.class);
    private final HousekeepingService service = new HousekeepingService(rollup, retention, dailyStats);

    @Test
    void summarisesBeforeItDeletes() {
        when(rollup.rollUp(TODAY)).thenReturn(TODAY.minusDays(1));

        service.run(NOW);

        InOrder order = inOrder(rollup, retention);
        order.verify(rollup).rollUp(TODAY);
        order.verify(retention).purge(TODAY.minusDays(1), NOW);
    }

    @Test
    void failedRollupLimitsRetentionToWhatIsAlreadySummarised() {
        when(rollup.rollUp(TODAY)).thenThrow(new IllegalStateException("database hiccup"));
        when(dailyStats.latestDay()).thenReturn(TODAY.minusDays(5));

        service.run(NOW);

        verify(retention).purge(TODAY.minusDays(5), NOW);
    }

    @Test
    void refusesAnInvalidCron() {
        assertThatThrownBy(() -> new HousekeepingProperties(true, "0", 62, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pulseguard.housekeeping.cron");
    }

    @Test
    void refusesARawHistoryShorterThanTheDashboardReads() {
        assertThatThrownBy(() -> new HousekeepingProperties(true, "0 15 3 * * ?", 30, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 60");
    }
}
