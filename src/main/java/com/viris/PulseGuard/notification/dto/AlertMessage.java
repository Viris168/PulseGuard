package com.viris.PulseGuard.notification.dto;

import com.viris.PulseGuard.enumeration.AlertLevel;

import java.time.Instant;
import java.util.List;

/**
 * Channel-neutral alert content; each sender decides how to present it.
 *
 * @param subject one line that carries status and monitor name (email subject, Slack header,
 *                push-notification preview).
 * @param body    the full plain-text message, laid out for email.
 * @param level   how urgent it is; Slack shows it as a coloured bar.
 * @param fields  the same details as labelled values, for channels that lay them out themselves
 *                (Slack). Empty when a message has none, e.g. a test alert.
 * @param footer  closing note for such channels; null when there is none.
 * @param link    where to see the details in PulseGuard; null when there is nothing to open.
 */
public record AlertMessage(String subject, String body, AlertLevel level, List<Field> fields, String footer,
                           String link) {

    /**
     * @param at the moment a time field stands for, so channels can show it in the reader's own
     *           time zone; {@code value} is then the UTC fallback. Null for other fields.
     */
    public record Field(String label, String value, Instant at) {

        public Field(String label, String value) {
            this(label, value, null);
        }
    }

    public AlertMessage {
        level = level == null ? AlertLevel.INFO : level;
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** A message with no structured details. */
    public AlertMessage(String subject, String body) {
        this(subject, body, AlertLevel.INFO, List.of(), null, null);
    }
}
