package com.viris.PulseGuard.auth.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMailerTest {

    @Test
    void aNameSavedWithLineBreaksIsShownOnOneLine() {
        assertThat(AccountMailer.oneLine("Eve\r\n\r\nUrgent: visit evil.example\t"))
                .isEqualTo("Eve Urgent: visit evil.example");
    }
}
