package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.enumeration.MonitorType;
import com.viris.PulseGuard.monitor.Monitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatScheduleTest {

    private static Monitor hourlyWithTenMinutesGrace() {
        Monitor m = new Monitor();
        m.setType(MonitorType.HEARTBEAT);
        m.setIntervalSeconds(3600);
        m.setGraceSeconds(600);
        return m;
    }

    @Test
    void theDeadlineIsOnePeriodPlusGraceAfterThePing() {
        Instant ping = Instant.parse("2026-01-01T10:00:00Z");

        assertThat(HeartbeatSchedule.deadlineAfter(ping, hourlyWithTenMinutesGrace()))
                .isEqualTo(Instant.parse("2026-01-01T11:10:00Z"));
    }

    @Test
    void afterAMissTheNextDeadlineIsOnePeriodLater() {
        Instant missed = Instant.parse("2026-01-01T11:10:00Z");
        Instant now = missed.plusSeconds(30);

        assertThat(HeartbeatSchedule.nextDeadlineAfterMiss(missed, hourlyWithTenMinutesGrace(), now))
                .isEqualTo(Instant.parse("2026-01-01T12:10:00Z"));
    }

    @Test
    void aLongSilenceSkipsAheadInsteadOfReplayingEveryMissedPeriod() {
        Instant missed = Instant.parse("2026-01-01T11:10:00Z");
        Instant now = Instant.parse("2026-01-03T09:00:00Z"); // two days later

        Instant next = HeartbeatSchedule.nextDeadlineAfterMiss(missed, hourlyWithTenMinutesGrace(), now);

        assertThat(next).isAfter(now).isEqualTo(Instant.parse("2026-01-03T09:10:00Z"));
    }

    @Test
    void describesTheScheduleLikeTheDashboard() {
        assertThat(HeartbeatSchedule.missedMessage(hourlyWithTenMinutesGrace()))
                .isEqualTo("No ping received within 1h (+10m grace)");
        Monitor daily = hourlyWithTenMinutesGrace();
        daily.setIntervalSeconds(86_400);
        daily.setGraceSeconds(0);
        assertThat(HeartbeatSchedule.describe(daily)).isEqualTo("1d");
        assertThat(HeartbeatSchedule.humanize(5400)).isEqualTo("1h 30m");
        assertThat(HeartbeatSchedule.humanize(45)).isEqualTo("45s");
    }

    @Test
    void tokensAreUnguessableAndRecognisable() {
        String token = HeartbeatSchedule.newToken();

        assertThat(token).hasSize(24).matches("[A-Za-z0-9]{24}");
        assertThat(HeartbeatSchedule.looksLikeToken(token)).isTrue();
        assertThat(HeartbeatSchedule.looksLikeToken("../../etc/passwd")).isFalse();
        assertThat(HeartbeatSchedule.newToken()).isNotEqualTo(token);
    }
}
