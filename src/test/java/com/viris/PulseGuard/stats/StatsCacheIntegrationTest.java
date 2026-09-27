package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.StatsRange;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stats cache against a real Redis: hits, key layout, TTL, JSON form, and ownership on a warm cache. */
@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class StatsCacheIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MonitorStatsService statsService;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    CheckRepository checks;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private User owner;
    private Monitor monitor;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        users.deleteAll();
        owner = users.save(User.builder().name("Owner").email("owner@example.com").passwordHash("x").build());
        monitor = new Monitor();
        monitor.setUser(owner);
        monitor.setName("API");
        monitor.setUrl("https://example.com");
        monitor = monitors.save(monitor);
        Check check = new Check();
        check.setMonitor(monitor);
        check.setResult(CheckResult.UP);
        check.setResponseTimeMs(120);
        check.setCheckedAt(Instant.now().minus(Duration.ofMinutes(5)));
        checks.save(check);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long statementsFor(Runnable call) {
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void theSecondRequestIsServedFromRedisAndRoundTripsIntact() {
        MonitorRangeStats[] results = new MonitorRangeStats[2];

        long cold = statementsFor(() -> results[0] = statsService.stats(owner.getId(), monitor.getId(), StatsRange.H24));
        long warm = statementsFor(() -> results[1] = statsService.stats(owner.getId(), monitor.getId(), StatsRange.H24));

        // Warm: only the ownership and plan lookups hit Postgres; the 4 stats queries do not.
        assertThat(warm).isLessThan(cold);
        assertThat(cold - warm).isEqualTo(4);
        // JSON out of Redis rebuilds an equal record: range enum, Instants, nested lists, nulls.
        assertThat(results[1]).isEqualTo(results[0]);
    }

    @Test
    void storesReadableJsonUnderAPredictableKeyWithAnExpiry() {
        statsService.stats(owner.getId(), monitor.getId(), StatsRange.H24);

        String key = "monitorStats::" + monitor.getId() + ":24h:true";
        String json = redis.opsForValue().get(key);
        assertThat(json).startsWith("{").contains("\"range\":\"24h\"").contains("\"avgResponseMs\":120");
        // Default test TTL is 60s: the entry must expire on its own.
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    void aWarmCacheStillChecksOwnershipOnEveryRequest() {
        statsService.stats(owner.getId(), monitor.getId(), StatsRange.H24);
        User stranger = users.save(User.builder().name("Bob").email("bob@example.com").passwordHash("x").build());

        assertThatThrownBy(() -> statsService.stats(stranger.getId(), monitor.getId(), StatsRange.H24))
                .isInstanceOf(MonitorNotFoundException.class);
    }

    @Test
    void anUpgradeIsACacheMissNotAStaleAnswer() {
        // FREE: 7d without comparison (the week before is outside 7 days of history).
        assertThat(statsService.stats(owner.getId(), monitor.getId(), StatsRange.D7).previous()).isNull();
        owner.setPlan(Plan.PRO);
        users.save(owner);

        statsService.stats(owner.getId(), monitor.getId(), StatsRange.D7);

        assertThat(redis.hasKey("monitorStats::" + monitor.getId() + ":7d:false")).isTrue();
        assertThat(redis.hasKey("monitorStats::" + monitor.getId() + ":7d:true")).isTrue();
    }
}
