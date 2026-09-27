package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.enumeration.ChannelType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetMaskerTest {

    @Test
    void showsTheUsersOwnEmailAddress() {
        assertThat(TargetMasker.mask(ChannelType.EMAIL, "owner@example.com")).isEqualTo("owner@example.com");
    }

    @Test
    void reducesWebhookUrlsToTheirHost() {
        assertThat(TargetMasker.mask(ChannelType.SLACK, "https://hooks.slack.com/services/T000/B000/XYZ"))
                .isEqualTo("https://hooks.slack.com/••••");
        assertThat(TargetMasker.mask(ChannelType.WEBHOOK, "https://user:pw@api.example.com/hook?token=abc"))
                .isEqualTo("https://api.example.com/••••")
                .doesNotContain("pw").doesNotContain("abc");
    }

    @Test
    void keepsOnlyTheLastFourCharactersOfOtherTargets() {
        assertThat(TargetMasker.mask(ChannelType.SMS, "+85512345678")).isEqualTo("••••5678");
        assertThat(TargetMasker.mask(ChannelType.TELEGRAM, "123")).isEqualTo("••••");
    }

    @Test
    void neverEchoesAnUnparseableUrl() {
        assertThat(TargetMasker.mask(ChannelType.WEBHOOK, "https://bad url with secret")).isEqualTo("••••");
    }
}
