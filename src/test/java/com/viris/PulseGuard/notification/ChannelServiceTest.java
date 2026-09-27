package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.common.exception.InvalidChannelTargetException;
import com.viris.PulseGuard.enumeration.ChannelType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelServiceTest {

    @Test
    void lowercasesAndTrimsEmail() {
        assertThat(ChannelService.normalize(ChannelType.EMAIL, " Ops@Example.COM ")).isEqualTo("ops@example.com");
    }

    @Test
    void stripsSpacesAndDashesFromPhoneNumbers() {
        assertThat(ChannelService.normalize(ChannelType.SMS, "+855 12-345 678")).isEqualTo("+85512345678");
    }

    @Test
    void rejectsPhoneNumbersWithoutCountryCode() {
        assertThatThrownBy(() -> ChannelService.normalize(ChannelType.SMS, "012345678"))
                .isInstanceOf(InvalidChannelTargetException.class);
    }

    @Test
    void acceptsOnlySlackHostedWebhooks() {
        assertThat(ChannelService.normalize(ChannelType.SLACK, "https://hooks.slack.com/services/T/B/x"))
                .isEqualTo("https://hooks.slack.com/services/T/B/x");
        assertThatThrownBy(() -> ChannelService.normalize(ChannelType.SLACK, "https://169.254.169.254/services/x"))
                .isInstanceOf(InvalidChannelTargetException.class);
        assertThatThrownBy(() -> ChannelService.normalize(ChannelType.SLACK, "http://hooks.slack.com/services/x"))
                .isInstanceOf(InvalidChannelTargetException.class);
    }

    @Test
    void rejectsTypesWithoutValidation() {
        assertThatThrownBy(() -> ChannelService.normalize(ChannelType.WEBHOOK, "https://example.com"))
                .isInstanceOf(InvalidChannelTargetException.class);
    }
}
