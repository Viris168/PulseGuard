package com.viris.PulseGuard.notification.channels;

import com.viris.PulseGuard.enumeration.AlertLevel;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.dto.AlertMessage.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The real sender and RestClient, with Slack replaced by an in-process mock: nothing leaves the machine. */
class SlackSenderTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/secretToken123";

    private MockRestServiceServer slack;
    private SlackSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        slack = MockRestServiceServer.bindTo(builder).build();
        sender = new SlackSender(builder.build());
    }

    private static AlertMessage downAlert(String name) {
        return new AlertMessage("🔴 DOWN: " + name, "email body", AlertLevel.CRITICAL,
                List.of(new Field("URL", "https://api.example.com/health"),
                        new Field("Cause", "HTTP 500"),
                        new Field("Since", "2026-09-28 02:13:00 UTC")),
                "You'll get another message when it recovers.", null);
    }

    @Test
    void handlesSlackChannels() {
        assertThat(sender.type()).isEqualTo(ChannelType.SLACK);
    }

    @Test
    void postsHeaderFieldsAndFooterWithAFallbackText() {
        slack.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("🔴 DOWN: Shop API"))
                .andExpect(jsonPath("$.attachments[0].blocks", hasSize(3)))
                .andExpect(jsonPath("$.attachments[0].blocks[0].type").value("header"))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.type").value("plain_text"))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.text").value("🔴 DOWN: Shop API"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].type").value("section"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields", hasSize(3)))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields[0].type").value("mrkdwn"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields[0].text").value("*URL*\nhttps://api.example.com/health"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields[1].text").value("*Cause*\nHTTP 500"))
                .andExpect(jsonPath("$.attachments[0].color").value("#E01E5A"))
                .andExpect(jsonPath("$.attachments[0].blocks[2].type").value("context"))
                .andExpect(jsonPath("$.attachments[0].blocks[2].elements[0].text")
                        .value("PulseGuard · You'll get another message when it recovers."))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, downAlert("Shop API"));

        slack.verify();
    }

    @Test
    void userTextCannotPingTheChannelOrFakeALink() {
        AlertMessage message = new AlertMessage("🔴 DOWN: <!channel> & <https://evil.example|Open>", "b",
                AlertLevel.CRITICAL, List.of(new Field("Cause", "<!here> <@U123> & more")), null, null);
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text").value("🔴 DOWN: &lt;!channel&gt; &amp; &lt;https://evil.example|Open&gt;"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields[0].text").value("*Cause*\n&lt;!here&gt; &lt;@U123&gt; &amp; more"))
                // plain_text is never parsed by Slack, so the header shows the name as typed.
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.text").value("🔴 DOWN: <!channel> & <https://evil.example|Open>"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, message);

        slack.verify();
    }

    @Test
    void messageWithoutFieldsIsSentAsOneEscapedSection() {
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].blocks[1].type").value("section"))
                .andExpect(jsonPath("$.attachments[0].blocks[1].text.text").value("Nothing is down &amp; all is well."))
                .andExpect(jsonPath("$.attachments[0].blocks[2].elements[0].text").value("PulseGuard"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, new AlertMessage("🔔 Test alert from PulseGuard", "Nothing is down & all is well."));

        slack.verify();
    }

    @Test
    void cutsTextToSlacksBlockLimits() {
        String longName = "x".repeat(400);
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.text").value("🔴 DOWN: " + "x".repeat(150 - 10) + "…"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, downAlert(longName));

        slack.verify();
    }

    @Test
    void colourBarFollowsTheAlertLevel() {
        assertThat(SlackSender.color(AlertLevel.CRITICAL)).isEqualTo("#E01E5A");
        assertThat(SlackSender.color(AlertLevel.RESOLVED)).isEqualTo("#2EB67D");
        assertThat(SlackSender.color(AlertLevel.INFO)).isEqualTo("#6B7280");
    }

    @Test
    void recoveryIsGreenWithAButtonToTheIncident() {
        AlertMessage message = new AlertMessage("✅ RECOVERED: Shop API", "b", AlertLevel.RESOLVED,
                List.of(new Field("Down for", "14m 30s")), null, "https://app.pulseguard.test/incidents/42");
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].color").value("#2EB67D"))
                .andExpect(jsonPath("$.attachments[0].blocks", hasSize(4)))
                .andExpect(jsonPath("$.attachments[0].blocks[2].type").value("actions"))
                .andExpect(jsonPath("$.attachments[0].blocks[2].elements[0].type").value("button"))
                .andExpect(jsonPath("$.attachments[0].blocks[2].elements[0].text.text").value("Open in PulseGuard"))
                .andExpect(jsonPath("$.attachments[0].blocks[2].elements[0].url").value("https://app.pulseguard.test/incidents/42"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, message);

        slack.verify();
    }

    @Test
    void noButtonWithoutASafeLink() {
        AlertMessage message = new AlertMessage("🔴 DOWN: Shop API", "b", AlertLevel.CRITICAL,
                List.of(new Field("Cause", "HTTP 500")), null, "javascript:alert(1)");
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].blocks", hasSize(3)))
                .andExpect(jsonPath("$.attachments[0].blocks[*].type", not(hasItem("actions"))))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, message);

        slack.verify();
    }

    @Test
    void timesAreShownInEachReadersTimeZoneWithUtcAsFallback() {
        Instant since = Instant.parse("2026-09-28T02:13:00Z");
        AlertMessage message = new AlertMessage("🔴 DOWN: Shop API", "b", AlertLevel.CRITICAL,
                List.of(new Field("Since", "2026-09-28 02:13:00 UTC", since)), null, null);
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].blocks[1].fields[0].text").value(
                        "*Since*\n<!date^" + since.getEpochSecond()
                                + "^{date_short_pretty} at {time_secs}|2026-09-28 02:13:00 UTC>"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, message);

        slack.verify();
    }

    @Test
    void testAlertIsGreyAndHasNoButton() {
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].color").value("#6B7280"))
                .andExpect(jsonPath("$.attachments[0].blocks", hasSize(3)))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, new AlertMessage("🔔 Test alert from PulseGuard", "Nothing is down."));

        slack.verify();
    }

    @Test
    void neverCutsAnEmojiInHalf() {
        // 148 x's then an emoji: the cut at 149 would land between its two halves.
        AlertMessage message = new AlertMessage("x".repeat(148) + "🔴🔴🔴", "b");
        slack.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.text").value("x".repeat(148) + "…"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(WEBHOOK, message);

        slack.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.slack.com/services/T/B/x",            // not https
            "https://evil.example.com/services/T/B/x",          // another host
            "https://hooks.slack.com.evil.example/services/x",  // lookalike host
            "https://user@hooks.slack.com/services/T/B/x",      // user info
            "https://hooks.slack.com:8443/services/T/B/x",      // custom port
            "https://hooks.slack.com/api/chat.postMessage",     // not a webhook path
            "not a url at all",
    })
    void refusesAnythingButASlackIncomingWebhook(String target) {
        assertThatThrownBy(() -> sender.send(target, downAlert("Shop API")))
                .isInstanceOf(SlackDeliveryException.class)
                .hasMessageNotContaining(target);

        slack.verify(); // no request was made
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "400, invalid_payload",
            "403, action_prohibited",
            "404, no_service",
            "410, channel_is_archived",
            "429, ''",
            "500, rollup_error",
    })
    void slackErrorsBecomeDeliveryFailuresWithoutTheUrl(int status, String error) {
        slack.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.TEXT_PLAIN).body(error));

        assertThatThrownBy(() -> sender.send(WEBHOOK, downAlert("Shop API")))
                .isInstanceOfSatisfying(SlackDeliveryException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).contains(String.valueOf(status)).contains(error)
                            .doesNotContain("secretToken123").doesNotContain("hooks.slack.com");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void unexpectedErrorBodiesAreNotEchoed() {
        slack.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_HTML).body("<html>proxy error for " + WEBHOOK + "</html>"));

        assertThatThrownBy(() -> sender.send(WEBHOOK, downAlert("Shop API")))
                .isInstanceOf(SlackDeliveryException.class)
                .hasMessage("Slack rejected the alert: 502");
    }

    @Test
    void networkFailureDoesNotLeakTheUrl() {
        // The HTTP client's own message quotes the request URL; none of it may survive.
        slack.expect(requestTo(WEBHOOK)).andRespond(withException(new IOException("Connection reset")));

        assertThatThrownBy(() -> sender.send(WEBHOOK, downAlert("Shop API")))
                .isInstanceOfSatisfying(SlackDeliveryException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("secretToken123").doesNotContain("hooks.slack.com");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getStatus()).isZero();
                });
    }
}
