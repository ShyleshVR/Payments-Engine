package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PaymentEventData;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadFactoryTest {

    private final WebhookPayloadFactory factory = new WebhookPayloadFactory(new ObjectMapper().findAndRegisterModules());

    @Test
    void rendersTheVersionedMerchantContractInAFixedFieldOrder() {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID paymentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID merchantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PaymentEvent event = new PaymentEvent(
                eventId,
                "PAYMENT_COMPLETED",
                LocalDateTime.of(2026, 10, 5, 8, 30, 15),
                new PaymentEventData(paymentId, new BigDecimal("200.00"), "USD", merchantId, null, null)
        );

        String json = factory.render(factory.create(event));

        assertThat(json).isEqualTo("{"
                + "\"payloadVersion\":\"1\","
                + "\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PAYMENT_COMPLETED\","
                + "\"paymentId\":\"pay_22222222-2222-2222-2222-222222222222\","
                + "\"merchantId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"amount\":200.00,"
                + "\"currency\":\"USD\","
                + "\"occurredAt\":\"2026-10-05T08:30:15Z\""
                + "}");
    }
}
