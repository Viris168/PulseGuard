package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.notification.channels.SlackProperties;
import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** {@code @EnableAsync}: alerts are sent on the task pool (bounded in application.yaml), never on a check thread. */
@Configuration
@EnableAsync
@EnableConfigurationProperties({NotificationProperties.class, SlackProperties.class})
public class NotificationConfig {

    /**
     * Blocking is fine here: alerts already run on their own pool. Redirects are never followed,
     * so a response can never send the post (and the alert) somewhere other than Slack.
     */
    @Bean
    public RestClient slackRestClient(SlackProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
