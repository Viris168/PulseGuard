package com.viris.PulseGuard.billing;

import com.stripe.StripeClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link StripeClient} for the app. Instance-based on purpose: the SDK's static
 * {@code Stripe.apiKey} is global mutable state that tests would leak into each other.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class BillingConfig {

    @Bean
    public StripeClient stripeClient(StripeProperties properties) {
        return StripeClient.builder()
                .setApiKey(properties.secretKey())
                .setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()))
                .setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()))
                .setMaxNetworkRetries(properties.maxNetworkRetries())
                .build();
    }
}
