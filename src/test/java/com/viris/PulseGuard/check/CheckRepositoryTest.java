package com.viris.PulseGuard.check;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.monitor.Monitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    CheckRepository checks;

    private Check newCheck(Monitor monitor, Instant at) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(CheckResult.UP);
        check.setStatusCode(200);
        check.setResponseTimeMs(120);
        check.setCheckedAt(at);
        return em.persistAndFlush(check);
    }

    @Test
    void returnsLatestChecksFirstAndHonoursPageSize() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Instant now = Instant.now();
        newCheck(monitor, now.minus(3, ChronoUnit.MINUTES));
        Check newest = newCheck(monitor, now);
        Check middle = newCheck(monitor, now.minus(1, ChronoUnit.MINUTES));

        List<Check> page = checks.findByMonitorIdOrderByCheckedAtDesc(monitor.getId(), PageRequest.of(0, 2));

        assertThat(page).extracting(Check::getId).containsExactly(newest.getId(), middle.getId());
    }

    @Test
    void doesNotMixChecksOfDifferentMonitors() {
        var user = newUser("a@example.com");
        Monitor m1 = newMonitor(user, "m1");
        Monitor m2 = newMonitor(user, "m2");
        newCheck(m1, Instant.now());
        newCheck(m2, Instant.now());

        assertThat(checks.findByMonitorIdOrderByCheckedAtDesc(m1.getId(), PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void findsChecksWithinTimeRange() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Instant now = Instant.now();
        newCheck(monitor, now.minus(10, ChronoUnit.DAYS));
        Check recent = newCheck(monitor, now.minus(1, ChronoUnit.HOURS));

        List<Check> found = checks.findByMonitorIdAndCheckedAtBetween(
                monitor.getId(), now.minus(1, ChronoUnit.DAYS), now);

        assertThat(found).extracting(Check::getId).containsExactly(recent.getId());
    }

    @Test
    void retentionDeleteRemovesOnlyChecksOlderThanCutoff() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Instant now = Instant.now();
        newCheck(monitor, now.minus(40, ChronoUnit.DAYS));
        Check keep = newCheck(monitor, now.minus(1, ChronoUnit.DAYS));

        int deleted = checks.deleteOlderThan(now.minus(30, ChronoUnit.DAYS));
        em.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(checks.findAll()).extracting(Check::getId).containsExactly(keep.getId());
    }

    @Test
    void deletingMonitorCascadesToChecks() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        newCheck(monitor, Instant.now());

        em.getEntityManager().createNativeQuery("delete from monitors where id = ?1")
                .setParameter(1, monitor.getId()).executeUpdate();
        em.clear();

        assertThat(checks.count()).isZero();
    }
}
