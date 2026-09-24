package com.viris.PulseGuard.scheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The Quartz scheduler itself is Boot auto-configuration driven by {@code spring.quartz.*};
 * this only binds PulseGuard's own scheduling settings.
 */
@Configuration
@EnableConfigurationProperties(SchedulingProperties.class)
public class SchedulingConfig {
}
