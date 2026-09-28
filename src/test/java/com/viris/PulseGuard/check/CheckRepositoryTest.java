package com.viris.PulseGuard.check;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.enumeration.Plan;
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
    void retentionDeletesOnlyOldChecksOfThatPlan() {
        Monitor free = newMonitor(newUser("free@example.com"), "free");
        User proUser = newUser("pro@example.com");
        proUser.setPlan(Plan.PRO);
        em.persistAndFlush(proUser);
        Monitor pro = newMonitor(proUser, "pro");
        Instant now = Instant.now();
        newCheck(free, now.minus(40, ChronoUnit.DAYS));
        Check keepFree = newCheck(free, now.minus(1, ChronoUnit.DAYS));
        Check keepPro = newCheck(pro, now.minus(40, ChronoUnit.DAYS));

        int deleted = checks.deleteBatchForPlanBefore("FREE", now.minus(30, ChronoUnit.DAYS), 100);
        em.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(checks.findAll()).extracting(Check::getId)
                .containsExactlyInAnyOrder(keepFree.getId(), keepPro.getId());
    }

    @Test
    void retentionDeletesAtMostOneBatch() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            newCheck(monitor, old.plusSeconds(i));
        }

        assertThat(checks.deleteBatchForPlanBefore("FREE", Instant.now(), 2)).isEqualTo(2);
        assertThat(checks.count()).isEqualTo(3);
    }

    @Test
    void findsTheOldestCheck() {
        assertThat(checks.oldestCheckedAt()).isNull();
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        newCheck(monitor, oldest.plusSeconds(60));
        newCheck(monitor, oldest);

        assertThat(checks.oldestCheckedAt()).isEqualTo(oldest);
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
