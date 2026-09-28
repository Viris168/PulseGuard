package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.notification.channels.SlackProperties;
import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationConfig.class)
            .withPropertyValues(
                    "pulseguard.notification.from=PulseGuard <alerts@x.io>",
                    "pulseguard.notification.slack.connect-timeout=5s",
                    "pulseguard.notification.slack.read-timeout=10s");

    @Test
    void bindsTheSenderAddress() {
        runner.run(context -> assertThat(context.getBean(NotificationProperties.class).from())
                .isEqualTo("PulseGuard <alerts@x.io>"));
    }

    @Test
    void refusesToStartWithoutASenderAddress() {
        runner.withPropertyValues("pulseguard.notification.from=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsSlackTimeoutsAndBuildsTheSlackClient() {
        runner.run(context -> {
            SlackProperties slack = context.getBean(SlackProperties.class);
            assertThat(slack.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(slack.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(context).getBean("slackRestClient").isInstanceOf(RestClient.class);
        });
    }

    @Test
    void refusesToStartWithoutSlackTimeouts() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationConfig.class)
                .withPropertyValues("pulseguard.notification.from=PulseGuard <alerts@x.io>")
                .run(context -> assertThat(context).hasFailed());
    }
}
