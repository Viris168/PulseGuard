package com.viris.PulseGuard.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppConfig.class);

    @Test
    void buildsFrontendLinksWithoutADoubleSlash() {
        runner.withPropertyValues("pulseguard.app.base-url=https://app.pulseguard.io/")
                .run(context -> assertThat(context.getBean(AppProperties.class).url("/incidents/42"))
                        .isEqualTo("https://app.pulseguard.io/incidents/42"));
    }

    @Test
    void refusesToStartWithoutABaseUrl() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithANonHttpBaseUrl() {
        runner.withPropertyValues("pulseguard.app.base-url=javascript:alert(1)")
                .run(context -> assertThat(context).hasFailed());
    }
}
