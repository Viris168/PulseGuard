package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationConfig.class);

    @Test
    void bindsTheSenderAddress() {
        runner.withPropertyValues("pulseguard.notification.from=PulseGuard <alerts@x.io>")
                .run(context -> assertThat(context.getBean(NotificationProperties.class).from())
                        .isEqualTo("PulseGuard <alerts@x.io>"));
    }

    @Test
    void refusesToStartWithoutASenderAddress() {
        runner.withPropertyValues("pulseguard.notification.from=")
                .run(context -> assertThat(context).hasFailed());
    }
}
