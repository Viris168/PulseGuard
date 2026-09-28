package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.monitor.Monitor;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/** Deadlines, wording and tokens for heartbeat monitors. Pure; no state beyond the random source. */
public final class HeartbeatSchedule {

    /** 24 base62 characters, about 143 bits: unguessable, still short enough to paste. */
    static final int TOKEN_LENGTH = 24;

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private HeartbeatSchedule() {
    }

    public static String newToken() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return token.toString();
    }

    /** Cheap shape check before touching the database. */
    public static boolean looksLikeToken(String token) {
        if (token.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** When a ping at {@code pingAt} must be followed by the next one: one period plus grace. */
    public static Instant deadlineAfter(Instant pingAt, Monitor monitor) {
        return pingAt.plusSeconds(monitor.getIntervalSeconds()).plusSeconds(grace(monitor));
    }

    /**
     * The deadline after a miss at {@code missedAt}: one period later, or the first such
     * deadline still ahead of {@code now}. Skipping ahead means a job that has been dead for
     * days is recorded as one miss, not replayed one missed period at a time.
     */
    public static Instant nextDeadlineAfterMiss(Instant missedAt, Monitor monitor, Instant now) {
        long period = monitor.getIntervalSeconds();
        Instant next = missedAt.plusSeconds(period);
        if (next.isAfter(now)) {
            return next;
        }
        long behind = Duration.between(next, now).toSeconds() / period + 1;
        return next.plusSeconds(behind * period);
    }

    /** "No ping received within 1h (+10m grace)": the incident cause and the failed check's message. */
    public static String missedMessage(Monitor monitor) {
        return "No ping received within " + describe(monitor);
    }

    /** "1h (+10m grace)", or "1h" without grace. */
    public static String describe(Monitor monitor) {
        int grace = grace(monitor);
        return humanize(monitor.getIntervalSeconds()) + (grace > 0 ? " (+" + humanize(grace) + " grace)" : "");
    }

    /** "45s", "10m", "1h", "1h 30m", "1d", "7d" — matches formatDuration in the frontend. */
    static String humanize(long seconds) {
        if (seconds % 86_400 == 0) {
            return seconds / 86_400 + "d";
        }
        if (seconds >= 3600) {
            long minutes = seconds % 3600 / 60;
            return seconds / 3600 + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    private static int grace(Monitor monitor) {
        return monitor.getGraceSeconds() == null ? 0 : monitor.getGraceSeconds();
    }
}
