package com.shylesh.payment_service.processor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param baseUrl        card processor API (processor-simulator)
 * @param apiKey         sent as X-Api-Key
 * @param connectTimeout fail fast when the processor is unreachable
 * @param readTimeout    a slower answer is treated as an unknown outcome and retried with the
 *                       same Idempotency-Key, which returns the original result
 */
@ConfigurationProperties(prefix = "payflow.processor")
public record ProcessorProperties(
        @DefaultValue("http://localhost:8086") String baseUrl,
        String apiKey,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {

    public ProcessorProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("payflow.processor.api-key must be set");
        }
    }
}
