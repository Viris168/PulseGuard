package com.viris.PulseGuard.auth.account;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountEmailProperties.class)
public class AccountEmailConfig {
}
