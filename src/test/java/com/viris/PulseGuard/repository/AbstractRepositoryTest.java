package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.model.Incident;
import com.viris.PulseGuard.model.Monitor;
import com.viris.PulseGuard.model.NotificationChannel;
import com.viris.PulseGuard.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs repository tests against a real PostgreSQL (Testcontainers) with the Flyway
 * migrations applied, so partial indexes and constraints are exercised for real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class AbstractRepositoryTest {

    // One container shared by every test class (Spring caches the context, so it must outlive a class).
    // Started once here; Ryuk removes it when the JVM exits.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestEntityManager em;

    protected User newUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("hash");
        return em.persistAndFlush(user);
    }

    protected Monitor newMonitor(User owner, String name) {
        Monitor monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName(name);
        monitor.setUrl("https://example.com/" + name);
        return em.persistAndFlush(monitor);
    }

    protected Incident newIncident(Monitor monitor, IncidentStatus status) {
        Incident incident = new Incident();
        incident.setMonitor(monitor);
        incident.setStatus(status);
        return em.persistAndFlush(incident);
    }

    protected NotificationChannel newChannel(User owner, ChannelType type, boolean enabled) {
        NotificationChannel channel = new NotificationChannel();
        channel.setUser(owner);
        channel.setType(type);
        channel.setTarget("target-" + type);
        channel.setEnabled(enabled);
        return em.persistAndFlush(channel);
    }
}
