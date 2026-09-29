package com.viris.PulseGuard.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /**
     * Runs model calls so the caller can stop waiting at {@code pulseguard.ai.timeout}. Virtual
     * threads: a call is almost all waiting on the provider, so each one costs next to nothing.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
