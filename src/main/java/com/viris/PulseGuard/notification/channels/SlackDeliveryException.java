package com.viris.PulseGuard.notification.channels;

/**
 * A Slack post that did not go through. The message holds the HTTP status and Slack's own error
 * code (e.g. {@code 404 no_service}) and never the webhook URL: the URL is the credential, and
 * HTTP client exceptions quote it, so they are never kept as a cause.
 */
public class SlackDeliveryException extends RuntimeException {

    private final int status;

    public SlackDeliveryException(int status, String slackError) {
        super("Slack rejected the alert: " + status + (slackError.isEmpty() ? "" : " " + slackError));
        this.status = status;
    }

    public SlackDeliveryException(String reason) {
        super("Slack alert not sent: " + reason);
        this.status = 0;
    }

    /** The HTTP status Slack answered with; 0 when it was never reached. */
    public int getStatus() {
        return status;
    }
}
