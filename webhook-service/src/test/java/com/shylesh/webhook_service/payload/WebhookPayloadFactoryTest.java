package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.EventEnvelope;
import com.shylesh.webhook_service.event.PaymentEventData;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WebhookPayloadFactory factory = new WebhookPayloadFactory(objectMapper);

    @Test
    void rendersVersionedPayloadWithStableFieldOrderAndUtcTimestamp() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        Map<String, Object> rawData = Map.of(
                "paymentId", paymentId.toString(),
                "amount", "200.00",
                "currency", "USD",
                "merchantId", merchantId.toString(),
                "customerId", UUID.randomUUID().toString()
        );
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "PAYMENT_COMPLETED",
                LocalDateTime.of(2026, 9, 23, 4, 12, 14, 461_000_000),
                objectMapper.valueToTree(rawData)
        );
        PaymentEventData data = objectMapper.convertValue(envelope.getData(), PaymentEventData.class);

        String json = factory.render(factory.create(envelope, data));

        assertThat(json).startsWith("{\"payloadVersion\":\"1\",\"eventId\":");

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("payloadVersion").isTextual()).isTrue();
        assertThat(node.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(node.get("eventType").asText()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(node.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(node.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(node.get("currency").asText()).isEqualTo("USD");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-09-23T04:12:14.461Z");

        // Internal-only fields must never leak into the merchant-facing contract.
        assertThat(node.has("customerId")).isFalse();
    }
}
