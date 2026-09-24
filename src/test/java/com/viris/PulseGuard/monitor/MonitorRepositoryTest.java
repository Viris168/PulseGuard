package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    MonitorRepository monitors;

    @Test
    void listsOnlyMonitorsOwnedByUser() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        newMonitor(alice, "a1");
        newMonitor(alice, "a2");
        newMonitor(bob, "b1");

        assertThat(monitors.findAllByUserId(alice.getId())).extracting(Monitor::getName)
                .containsExactlyInAnyOrder("a1", "a2");
        assertThat(monitors.countByUserId(bob.getId())).isEqualTo(1);
    }

    @Test
    void cannotFetchAnotherUsersMonitorById() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        Monitor aliceMonitor = newMonitor(alice, "a1");

        assertThat(monitors.findByIdAndUserId(aliceMonitor.getId(), alice.getId())).isPresent();
        assertThat(monitors.findByIdAndUserId(aliceMonitor.getId(), bob.getId())).isEmpty();
    }

    @Test
    void findsOnlyActiveMonitors() {
        User user = newUser("a@example.com");
        Monitor active = newMonitor(user, "active");
        Monitor paused = newMonitor(user, "paused");
        paused.setActive(false);
        em.persistAndFlush(paused);

        assertThat(monitors.findAllByActiveTrue()).extracting(Monitor::getId)
                .contains(active.getId())
                .doesNotContain(paused.getId());
    }

    @Test
    void appliesDatabaseDefaultsFromEntity() {
        Monitor saved = newMonitor(newUser("a@example.com"), "m");
        em.clear();

        Monitor loaded = monitors.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(MonitorState.UP);
        assertThat(loaded.getMethod()).isEqualTo("GET");
        assertThat(loaded.getIntervalSeconds()).isEqualTo(300);
        assertThat(loaded.getTimeoutMs()).isEqualTo(10000);
    }

    @Test
    void rejectsStaleUpdateWithOptimisticLocking() {
        Monitor stale = newMonitor(newUser("a@example.com"), "m");
        em.clear();

        Monitor fresh = monitors.findById(stale.getId()).orElseThrow();
        fresh.setState(MonitorState.SUSPICIOUS);
        monitors.saveAndFlush(fresh);
        em.clear();

        stale.setState(MonitorState.DOWN);
        assertThatThrownBy(() -> monitors.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void populatesCreatedAtFromTheDatabaseDefaultOnInsert() {
        // created_at is insertable=false; @Generated must read the DB value back,
        // otherwise the create response would carry null.
        Monitor monitor = newMonitor(newUser("created@example.com"), "m");

        assertThat(monitor.getCreatedAt()).isNotNull();
    }

    @Test
    void touchLastCheckedAtLeavesVersionAloneSoConcurrentEditsStillSave() {
        Monitor stale = newMonitor(newUser("touch@example.com"), "m");
        long versionBefore = stale.getVersion();
        Instant checkedAt = Instant.parse("2026-09-24T10:00:00Z");
        em.clear();

        assertThat(monitors.touchLastCheckedAt(stale.getId(), checkedAt)).isEqualTo(1);
        em.clear();

        Monitor loaded = monitors.findById(stale.getId()).orElseThrow();
        assertThat(loaded.getLastCheckedAt()).isEqualTo(checkedAt);
        assertThat(loaded.getVersion()).isEqualTo(versionBefore);

        // An edit made from a copy loaded before the check still goes through.
        stale.setName("renamed");
        monitors.saveAndFlush(stale);
    }

}
