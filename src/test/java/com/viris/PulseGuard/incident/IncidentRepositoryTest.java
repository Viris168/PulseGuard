package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    IncidentRepository incidents;

    @Test
    void findsOpenIncidentForMonitor() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        Incident open = newIncident(monitor, IncidentStatus.OPEN);

        assertThat(incidents.findByMonitorIdAndStatus(monitor.getId(), IncidentStatus.OPEN))
                .get().extracting(Incident::getId).isEqualTo(open.getId());
    }

    @Test
    void rejectsSecondOpenIncidentForSameMonitor() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        newIncident(monitor, IncidentStatus.OPEN);

        Incident second = new Incident();
        second.setMonitor(monitor);

        assertThatThrownBy(() -> incidents.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsNewOpenIncidentOnceThePreviousIsResolved() {
        Monitor monitor = newMonitor(newUser("a@example.com"), "m");
        newIncident(monitor, IncidentStatus.RESOLVED);
        newIncident(monitor, IncidentStatus.RESOLVED);

        Incident open = newIncident(monitor, IncidentStatus.OPEN);

        assertThat(open.getId()).isNotNull();
    }

    @Test
    void allowsOneOpenIncidentPerMonitor() {
        User user = newUser("a@example.com");
        newIncident(newMonitor(user, "m1"), IncidentStatus.OPEN);
        newIncident(newMonitor(user, "m2"), IncidentStatus.OPEN);

        assertThat(incidents.count()).isEqualTo(2);
    }

    @Test
    void scopesIncidentLookupsToOwningUser() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        Incident incident = newIncident(newMonitor(alice, "m"), IncidentStatus.OPEN);

        assertThat(incidents.findByIdAndMonitorUserId(incident.getId(), alice.getId())).isPresent();
        assertThat(incidents.findByIdAndMonitorUserId(incident.getId(), bob.getId())).isEmpty();
        assertThat(incidents.findAllByMonitorUserIdOrderByStartedAtDesc(bob.getId())).isEmpty();
        assertThat(incidents.findAllByMonitorUserIdOrderByStartedAtDesc(alice.getId())).hasSize(1);
    }
}
