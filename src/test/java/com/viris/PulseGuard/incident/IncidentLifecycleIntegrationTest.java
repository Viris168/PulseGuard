package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.scheduling.MonitorCheckService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole pipeline against real Postgres and a real HTTP target: check → state machine →
 * incident, driven through {@link MonitorCheckService} exactly as a Quartz fire would.
 */
@SpringBootTest
class IncidentLifecycleIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class TestBackends {
        /** MockWebServer listens on loopback, which the real SSRF check rightly blocks. */
        @Bean
        @Primary
        SafeUrlValidator permissiveUrlValidator() {
            return new SafeUrlValidator(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        }
    }

    @Autowired
    MonitorCheckService monitorCheckService;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    CheckRepository checks;
    @Autowired
    UserRepository users;

    private MockWebServer target;
    private Long monitorId;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors, checks and incidents follow by ON DELETE CASCADE

        target = new MockWebServer();
        target.start();

        User owner = new User();
        owner.setEmail("owner@example.com");
        owner.setName("Owner");
        owner.setPasswordHash("hash");
        users.save(owner);

        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("api");
        monitor.setUrl(target.url("/health").toString());
        monitor.setTimeoutMs(2000);
        monitorId = monitors.save(monitor).getId();
    }

    @AfterEach
    void stopTarget() throws Exception {
        target.shutdown();
    }

    private void respond(int... statuses) {
        for (int status : statuses) {
            target.enqueue(new MockResponse().setResponseCode(status));
        }
    }

    private void runChecks(int times) {
        for (int i = 0; i < times; i++) {
            monitorCheckService.runCheck(monitorId);
        }
    }

    private MonitorState state() {
        return monitors.findById(monitorId).orElseThrow().getState();
    }

    @Test
    void outageOpensOneIncidentAndRecoveryResolvesIt() {
        respond(500, 500);
        runChecks(2);
        assertThat(state()).isEqualTo(MonitorState.SUSPICIOUS);
        assertThat(incidents.findAll()).as("no incident before the threshold").isEmpty();

        respond(500);
        runChecks(1);
        assertThat(state()).isEqualTo(MonitorState.DOWN);
        List<Incident> opened = incidents.findAllByMonitorIdOrderByStartedAtDesc(monitorId);
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(opened.getFirst().getCause()).startsWith("STATUS_MISMATCH");

        respond(200, 200);
        runChecks(2);
        assertThat(state()).isEqualTo(MonitorState.UP);
        List<Incident> after = incidents.findAllByMonitorIdOrderByStartedAtDesc(monitorId);
        assertThat(after).as("resolved in place, not a second row").hasSize(1);
        assertThat(after.getFirst().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(after.getFirst().getResolvedAt()).isNotNull();

        assertThat(checks.count()).isEqualTo(5);
    }

    @Test
    void flickerDuringRecoveryKeepsTheSameIncident() {
        respond(500, 500, 500,   // down: incident opens
                200,             // recovering
                500,             // flicker: back to DOWN, same incident
                200, 200);       // recovered
        runChecks(7);

        List<Incident> all = incidents.findAllByMonitorIdOrderByStartedAtDesc(monitorId);
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(state()).isEqualTo(MonitorState.UP);
    }

    @Test
    void scatteredFailuresNeverOpenAnIncident() {
        respond(500, 200, 500, 200, 500, 200);
        runChecks(6);

        assertThat(incidents.findAll()).isEmpty();
        assertThat(state()).isEqualTo(MonitorState.UP);
    }
}
