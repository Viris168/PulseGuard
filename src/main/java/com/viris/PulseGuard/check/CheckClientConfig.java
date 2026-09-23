package com.viris.PulseGuard.check;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The outbound client used for monitor checks. Separate from anything the API serves:
 * the connect timeout lives here, while each request carries its own monitor's response
 * timeout.
 */
@Configuration
@EnableConfigurationProperties(CheckProperties.class)
public class CheckClientConfig {

    @Bean
    public WebClient checkWebClient(CheckProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.connectTimeout().toMillis())
                // Not followed on purpose: a redirect target has not been through the SSRF check.
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
