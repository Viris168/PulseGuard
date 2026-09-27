package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Redis cache for rendered public status pages. Same choices as StatsCacheConfig: JSON typed
 * to the one class it holds, and fail-open error handling (configured there, for every cache).
 */
@Configuration
@EnableConfigurationProperties(StatusPageProperties.class)
public class StatusPageCacheConfig {

    public static final String PUBLIC_STATUS_PAGES = "publicStatusPages";

    @Bean
    public RedisCacheManagerBuilderCustomizer publicStatusPagesCache(StatusPageProperties properties) {
        return builder -> builder.withCacheConfiguration(PUBLIC_STATUS_PAGES, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.cacheTtl())
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(PublicStatusPageResponse.class))));
    }
}
