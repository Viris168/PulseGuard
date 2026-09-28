package com.viris.PulseGuard.notification.dto;

import java.util.List;

/**
 * Channel-neutral alert content; each sender decides how to present it.
 *
 * @param subject one line that carries status and monitor name (email subject, Slack header,
 *                push-notification preview).
 * @param body    the full plain-text message, laid out for email.
 * @param fields  the same details as labelled values, for channels that lay them out themselves
 *                (Slack). Empty when a message has none, e.g. a test alert.
 * @param footer  closing note for such channels; null when there is none.
 */
public record AlertMessage(String subject, String body, List<Field> fields, String footer) {

    public record Field(String label, String value) {
    }

    public AlertMessage {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** A message with no structured details. */
    public AlertMessage(String subject, String body) {
        this(subject, body, List.of(), null);
    }
}
