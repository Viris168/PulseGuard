package com.viris.PulseGuard.notification.channels;

/**
 * Makes user text inert in Slack's mrkdwn. Slack reads {@code <...>} as a control sequence, so an
 * unescaped monitor named {@code <!channel>} would ping everyone in the channel, and
 * {@code <https://evil|Open dashboard>} would render as a disguised link. These three characters
 * are the only ones Slack requires escaped.
 */
final class SlackMarkdown {

    private SlackMarkdown() {
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        // & first, or the & in "&lt;" would be escaped again.
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
