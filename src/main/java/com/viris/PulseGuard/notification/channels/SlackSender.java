package com.viris.PulseGuard.notification.channels;

import com.viris.PulseGuard.enumeration.AlertLevel;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.repository.NotificationSender;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Posts alerts to a Slack incoming webhook as Block Kit inside a coloured attachment: a header,
 * the details as fields (times in each reader's time zone), an "Open in PulseGuard" button and a
 * footer. The top-level {@code text} is what phone notifications show.
 *
 * <p>Failures throw {@link SlackDeliveryException}, which the caller records as FAILED. No retry,
 * the same as email: an alert is delivered at most once.
 */
@Component
public class SlackSender implements NotificationSender {

    static final String SLACK_HOST = "hooks.slack.com";

    // Block Kit limits: longer text is rejected by Slack as invalid_blocks.
    private static final int HEADER_MAX = 150;
    private static final int FIELD_MAX = 2000;
    private static final int SECTION_MAX = 3000;
    private static final int FIELDS_MAX = 10;

    private final RestClient restClient;

    public SlackSender(@Qualifier("slackRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ChannelType type() {
        return ChannelType.SLACK;
    }

    @Override
    public void send(String target, AlertMessage message) {
        URI webhook = requireSlackWebhook(target);
        try {
            restClient.post()
                    .uri(webhook)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(message))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return null;
                        }
                        throw new SlackDeliveryException(response.getStatusCode().value(), errorCode(response.getBody()));
                    });
        } catch (SlackDeliveryException e) {
            throw e;
        } catch (RestClientException e) {
            // Its message names the request URL, so only the kind of failure is kept.
            throw new SlackDeliveryException("could not reach Slack (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The target was checked when the channel was saved; checked again here so an old or altered
     * row can never make the server post somewhere else. The host is fixed, so no DNS check is needed.
     */
    static URI requireSlackWebhook(String target) {
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException | NullPointerException e) {
            throw new SlackDeliveryException("target is not a Slack incoming webhook");
        }
        boolean valid = "https".equals(uri.getScheme())
                && uri.getHost() != null
                && SLACK_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))
                && uri.getRawUserInfo() == null
                && uri.getPort() == -1
                && uri.getRawPath() != null
                && uri.getRawPath().startsWith("/services/");
        if (!valid) {
            throw new SlackDeliveryException("target is not a Slack incoming webhook");
        }
        return uri;
    }

    /**
     * The whole message sits in one attachment, because only attachments get the coloured bar
     * down their left edge. The top-level {@code text} is what phone notifications show.
     */
    static Map<String, Object> payload(AlertMessage message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        // plain_text renders literally, so the header needs no escaping.
        blocks.add(Map.of("type", "header",
                "text", Map.of("type", "plain_text", "text", truncate(message.subject(), HEADER_MAX), "emoji", true)));

        if (message.fields().isEmpty()) {
            blocks.add(Map.of("type", "section",
                    "text", mrkdwn(truncate(SlackMarkdown.escape(message.body()), SECTION_MAX))));
        } else {
            List<Map<String, Object>> fields = message.fields().stream()
                    .limit(FIELDS_MAX)
                    .map(field -> mrkdwn(truncate("*" + SlackMarkdown.escape(field.label()) + "*\n"
                            + fieldValue(field), FIELD_MAX)))
                    .toList();
            blocks.add(Map.of("type", "section", "fields", fields));
        }

        if (isLinkable(message.link())) {
            blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
                    "type", "button",
                    "text", Map.of("type", "plain_text", "text", "Open in PulseGuard"),
                    "url", message.link()))));
        }

        String footer = message.footer() == null ? "PulseGuard" : "PulseGuard · " + message.footer();
        blocks.add(Map.of("type", "context", "elements", List.of(mrkdwn(SlackMarkdown.escape(footer)))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", SlackMarkdown.escape(message.subject()));
        payload.put("attachments", List.of(Map.of("color", color(message.level()), "blocks", blocks)));
        return payload;
    }

    static String color(AlertLevel level) {
        return switch (level) {
            case CRITICAL -> "#E01E5A";
            case RESOLVED -> "#2EB67D";
            case INFO -> "#6B7280";
        };
    }

    /**
     * A time field becomes Slack's date token, which each reader sees in their own time zone
     * ("Today at 2:13 AM"); the UTC text after the pipe is shown where Slack cannot render it.
     */
    private static String fieldValue(AlertMessage.Field field) {
        String text = SlackMarkdown.escape(field.value() == null ? "—" : field.value());
        if (field.at() == null) {
            return text;
        }
        return "<!date^" + field.at().getEpochSecond() + "^{date_short_pretty} at {time_secs}|" + text + ">";
    }

    /** Only http(s) links within Slack's 3000-character limit become a button; anything else is left out. */
    private static boolean isLinkable(String link) {
        return link != null && link.length() <= 3000
                && (link.startsWith("https://") || link.startsWith("http://"));
    }

    private static Map<String, Object> mrkdwn(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        // Never cut an emoji (a surrogate pair) in half: Slack rejects the broken character.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /** Slack answers errors with a short code such as {@code no_service}; anything else is dropped. */
    private static String errorCode(java.io.InputStream body) {
        try {
            String text = StreamUtils.copyToString(body, StandardCharsets.UTF_8).trim();
            return text.matches("[a-z_]{1,64}") ? text : "";
        } catch (IOException e) {
            return "";
        }
    }
}
