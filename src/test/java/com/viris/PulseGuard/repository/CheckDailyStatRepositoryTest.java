package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.model.CheckDailyStat;
import com.viris.PulseGuard.model.CheckDailyStatId;
import com.viris.PulseGuard.model.Monitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckDailyStatRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    CheckDailyStatRepository stats;

    private CheckDailyStat stat(Monitor monitor, LocalDate day, int total, int failed) {
        CheckDailyStat s = new CheckDailyStat();
        s.setId(new CheckDailyStatId(monitor.getId(), day));
        s.setMonitor(monitor);
        s.setTotalChecks(total);
        s.setFailedChecks(failed);
        s.setAvgResponseMs(100);
        s.setP95ResponseMs(250);
        return stats.saveAndFlush(s);
    }

    @Test
    void persistsAndLoadsByCompositeKey() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        LocalDate day = LocalDate.of(2026, 1, 15);
        stat(monitor, day, 288, 3);
        em.clear();

        CheckDailyStat loaded = stats.findById(new CheckDailyStatId(monitor.getId(), day)).orElseThrow();

        assertThat(loaded.getTotalChecks()).isEqualTo(288);
        assertThat(loaded.getFailedChecks()).isEqualTo(3);
        assertThat(loaded.getP95ResponseMs()).isEqualTo(250);
    }

    @Test
    void returnsStatsInDateRangeOrderedByDay() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Monitor other = newMonitor(newUser("b@example.com"), "o");
        stat(monitor, LocalDate.of(2026, 1, 3), 10, 0);
        stat(monitor, LocalDate.of(2026, 1, 1), 10, 1);
        stat(monitor, LocalDate.of(2026, 2, 1), 10, 2);
        stat(other, LocalDate.of(2026, 1, 2), 10, 3);

        List<CheckDailyStat> found = stats.findByIdMonitorIdAndIdDayBetweenOrderByIdDay(
                monitor.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(found).extracting(s -> s.getId().day())
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
    }
}
