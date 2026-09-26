package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.monitor.Monitor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMessageFactoryTest {

    private static final Instant STARTED = Instant.parse("2026-09-27T03:12:45Z");

    private final AlertMessageFactory factory = new AlertMessageFactory();

    private static Monitor monitor(String url) {
        Monitor monitor = new Monitor();
        monitor.setName("Payments API");
        monitor.setUrl(url);
        return monitor;
    }

    private static Incident incident(Instant resolvedAt) {
        Incident incident = new Incident();
        incident.setCause("TIMEOUT: No response");
        incident.setStartedAt(STARTED);
        incident.setResolvedAt(resolvedAt);
        return incident;
    }

    private final Monitor monitor = monitor("https://api.example.com/health");

    @Test
    void openedSubjectShowsStatusAndName() {
        assertThat(factory.opened(monitor, incident(null)).subject()).isEqualTo("🔴 DOWN: Payments API");
    }

    @Test
    void openedBodyIncludesCauseAndStartInUtc() {
        String body = factory.opened(monitor, incident(null)).body();

        assertThat(body)
                .contains("TIMEOUT: No response")
                .contains("2026-09-27 03:12:45 UTC")
                .contains("https://api.example.com/health");
    }

    @Test
    void resolvedSubjectShowsRecovery() {
        assertThat(factory.resolved(monitor, incident(STARTED.plusSeconds(60))).subject())
                .isEqualTo("✅ RECOVERED: Payments API");
    }

    @Test
    void resolvedBodyShowsHowLongItWasDown() {
        String body = factory.resolved(monitor, incident(STARTED.plus(Duration.ofMinutes(14).plusSeconds(30)))).body();

        assertThat(body)
                .contains("Down for:  14m 30s")
                .contains("2026-09-27 03:27:15 UTC");
    }

    @Test
    void humanizesShortAndLongDurations() {
        assertThat(AlertMessageFactory.humanize(Duration.ofSeconds(45))).isEqualTo("45s");
        assertThat(AlertMessageFactory.humanize(Duration.ofMinutes(125))).isEqualTo("2h 5m");
        assertThat(AlertMessageFactory.humanize(Duration.ZERO)).isEqualTo("0s");
    }

    @Test
    void neverLeaksCredentialsOrQueryFromTheUrl() {
        Monitor secretive = monitor("https://admin:s3cret@api.example.com/health?api_key=sk_live_abc123");

        String body = factory.opened(secretive, incident(null)).body();

        assertThat(body)
                .doesNotContain("s3cret")
                .doesNotContain("admin")
                .doesNotContain("sk_live")
                .contains("https://api.example.com/health?…");
    }

    @Test
    void keepsCustomPort() {
        assertThat(AlertMessageFactory.safeUrl("https://api.example.com:8443/x"))
                .isEqualTo("https://api.example.com:8443/x");
    }

    @Test
    void neverEchoesAnUnparseableUrl() {
        assertThat(AlertMessageFactory.safeUrl("not a url with secret=abc"))
                .isEqualTo("(invalid URL)");
    }
}
