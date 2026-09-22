package com.viris.PulseGuard.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Makes Redis calls fail fast.
 * <p>
 * Lettuce queues commands while a connection is down and only gives up at the command timeout
 * (60s by default). Every caller here is written to fail open, but that is useless if failing
 * takes a minute — a Redis outage would stall each login for the full timeout. Rejecting
 * commands immediately turns an outage into a fast, handled error.
 */
@Configuration
public class RedisConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastCustomizer(
            @Value("${spring.data.redis.timeout}") Duration commandTimeout) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(commandTimeout).build())
                .build());
    }
}
