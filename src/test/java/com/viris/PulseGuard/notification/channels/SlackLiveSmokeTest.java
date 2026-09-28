package com.viris.PulseGuard.notification.channels;

import com.viris.PulseGuard.common.config.AppProperties;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.notification.AlertMessageFactory;
import com.viris.PulseGuard.notification.NotificationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Posts a real DOWN and RECOVERED alert to a real Slack channel, through the production client,
 * message factory and sender. Skipped unless SLACK_TEST_WEBHOOK is set, so CI never calls Slack:
 *
 * <pre>SLACK_TEST_WEBHOOK=https://hooks.slack.com/services/... ./mvnw test -Dtest=SlackLiveSmokeTest</pre>
 *
 * Check the channel afterwards: two messages, and the monitor name must not have pinged anyone.
 */
@EnabledIfEnvironmentVariable(named = "SLACK_TEST_WEBHOOK", matches = "https://hooks\\.slack\\.com/services/.+")
class SlackLiveSmokeTest {

    @Test
    void postsADownAndARecoveredAlertToRealSlack() {
        String webhook = System.getenv("SLACK_TEST_WEBHOOK");
        SlackSender sender = new SlackSender(new NotificationConfig()
                .slackRestClient(new SlackProperties(Duration.ofSeconds(5), Duration.ofSeconds(10))));
        AlertMessageFactory messages = new AlertMessageFactory(new AppProperties(URI.create("http://localhost:5173")));

        Monitor monitor = new Monitor();
        monitor.setName("Smoke test <!channel> & Co");
        monitor.setUrl("https://admin:secret@api.example.com/health?api_key=sk_live_hidden");
        Incident incident = new Incident();
        incident.setId(1L); // so the alert carries an "Open in PulseGuard" button
        incident.setCause("STATUS_MISMATCH: Expected 200 but got 500");
        incident.setStartedAt(Instant.now().minus(Duration.ofMinutes(14).plusSeconds(30)));

        sender.send(webhook, messages.opened(monitor, incident));
        incident.setResolvedAt(Instant.now());
        sender.send(webhook, messages.resolved(monitor, incident));
    }
}
