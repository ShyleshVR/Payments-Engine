package com.shylesh.webhook_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient used for outbound merchant calls. Built from Spring Boot's auto-configured
 * RestClient.Builder so outbound requests are traced like everything else. Explicit
 * timeouts matter: the dispatcher processes deliveries one at a time, so a merchant
 * endpoint that hangs would otherwise stall delivery for every other merchant.
 */
@Configuration
public class WebhookHttpConfig {

    @Bean
    public RestClient webhookRestClient(
            RestClient.Builder builder,
            @Value("${webhook.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${webhook.http.read-timeout:10s}") Duration readTimeout) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return builder.requestFactory(requestFactory).build();
    }
}
