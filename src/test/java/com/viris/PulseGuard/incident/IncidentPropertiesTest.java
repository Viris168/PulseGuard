package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.incident.dto.IncidentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds the real config class the way the app does, without starting the whole app. */
class IncidentPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IncidentConfig.class);

    @Test
    void bindsThresholdsFromConfig() {
        runner.withPropertyValues(
                        "pulseguard.incident.failure-threshold=3",
                        "pulseguard.incident.recovery-threshold=2")
                .run(context -> {
                    IncidentProperties properties = context.getBean(IncidentProperties.class);
                    assertThat(properties.failureThreshold()).isEqualTo(3);
                    assertThat(properties.recoveryThreshold()).isEqualTo(2);
                });
    }

    @Test
    void refusesToStartWhenOneFailureWouldOpenAnIncident() {
        runner.withPropertyValues(
                        "pulseguard.incident.failure-threshold=1",
                        "pulseguard.incident.recovery-threshold=2")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("failureThreshold"));
    }

    @Test
    void refusesToStartWhenRecoveryNeedsNoPassingCheck() {
        runner.withPropertyValues(
                        "pulseguard.incident.failure-threshold=3",
                        "pulseguard.incident.recovery-threshold=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
