package com.viris.PulseGuard.notification.channels;

/**
 * A Slack post that did not go through. The message holds the HTTP status and Slack's own error
 * code (e.g. {@code 404 no_service}) and never the webhook URL: the URL is the credential, and
 * HTTP client exceptions quote it, so they are never kept as a cause.
 */
public class SlackDeliveryException extends RuntimeException {

    private final int status;
    private final boolean retryable;

    /** Slack answered. Worth retrying only when it was busy (429) or failing (5xx). */
    public SlackDeliveryException(int status, String slackError) {
        super("Slack rejected the alert: " + status + (slackError.isEmpty() ? "" : " " + slackError));
        this.status = status;
        this.retryable = status == 429 || status >= 500;
    }

    /** Slack was never reached; {@code retryable} says whether trying later could help. */
    public SlackDeliveryException(String reason, boolean retryable) {
        super("Slack alert not sent: " + reason);
        this.status = 0;
        this.retryable = retryable;
    }

    /** False for failures no retry can fix, such as a revoked (404) or malformed webhook. */
    public boolean isRetryable() {
        return retryable;
    }

    /** The HTTP status Slack answered with; 0 when it was never reached. */
    public int getStatus() {
        return status;
    }
}
