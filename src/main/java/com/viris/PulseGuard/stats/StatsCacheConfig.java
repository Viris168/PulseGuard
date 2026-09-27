package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.stats.dto.MonitorRangeStats;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Redis cache for dashboard stats.
 *
 * <p>Values are JSON through a serializer typed to {@link MonitorRangeStats}: readable in
 * redis-cli, no Serializable needed, and it can only ever rebuild that one type (a generic
 * "any class" JSON serializer is a deserialization-attack surface).
 *
 * <p>Fail open: a Redis error is logged and the call proceeds as a cache miss, so a Redis
 * outage makes the dashboard slower, never broken.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(StatsProperties.class)
public class StatsCacheConfig implements CachingConfigurer {

    public static final String MONITOR_STATS = "monitorStats";

    @Bean
    public RedisCacheManagerBuilderCustomizer monitorStatsCache(StatsProperties properties) {
        return builder -> builder.withCacheConfiguration(MONITOR_STATS, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.cacheTtl())
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(MonitorRangeStats.class))));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
