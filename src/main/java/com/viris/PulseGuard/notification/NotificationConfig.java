package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** {@code @EnableAsync}: alerts are sent on the task pool (bounded in application.yaml), never on a check thread. */
@Configuration
@EnableAsync
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {
}
