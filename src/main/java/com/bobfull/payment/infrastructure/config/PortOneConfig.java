package com.bobfull.payment.infrastructure.config;

import io.portone.sdk.server.PortOneClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import io.portone.sdk.server.webhook.WebhookVerifier;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class PortOneConfig {
    @Bean
    public PortOneClient portOneClient(PortOneProperties properties) {
        return new PortOneClient(properties.apiSecret(), "https://api.portone.io", properties.storeId());
    }
    @Bean
    public WebhookVerifier webhookVerifier(PortOneProperties properties) { return new WebhookVerifier(properties.webhookSecret()); }
    @Bean
    public RestClient portOneRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().baseUrl("https://api.portone.io").requestFactory(factory).build();
    }
}
