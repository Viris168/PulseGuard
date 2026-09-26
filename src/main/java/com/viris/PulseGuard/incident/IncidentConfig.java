package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.incident.dto.IncidentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IncidentProperties.class)
public class IncidentConfig {
}
