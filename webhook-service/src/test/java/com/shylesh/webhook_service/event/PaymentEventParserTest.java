package com.shylesh.webhook_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventParserTest {

    private final PaymentEventParser parser = new PaymentEventParser(new ObjectMapper().findAndRegisterModules());

    private final String eventId = UUID.randomUUID().toString();
    private final String paymentId = UUID.randomUUID().toString();
    private final String merchantId = UUID.randomUUID().toString();

    private String event(String eventIdJson, String data) {
        return "{\"eventId\":" + eventIdJson + ",\"eventType\":\"PAYMENT_COMPLETED\","
                + "\"occurredAt\":\"2026-10-05T08:00:00\",\"data\":" + data + "}";
    }

    private String data() {
        return "{\"paymentId\":\"" + paymentId + "\",\"amount\":25.00,\"currency\":\"USD\",\"merchantId\":\"" + merchantId + "\"}";
    }

    @Test
    void parsesAValidEvent() {
        PaymentEvent event = parser.parse(event("\"" + eventId + "\"", data()));

        assertThat(event.eventId()).hasToString(eventId);
        assertThat(event.data().getPaymentId()).hasToString(paymentId);
        assertThat(event.data().getMerchantId()).hasToString(merchantId);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Malformed payment event");
    }

    @Test
    void rejectsNullEventIdBeforeAnyDatabaseWork() {
        assertThatThrownBy(() -> parser.parse(event("null", data())))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsMissingDataAndNamesEveryMissingField() {
        assertThatThrownBy(() -> parser.parse(event("\"" + eventId + "\"", "{\"currency\":\"USD\"}")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("data.paymentId")
                .hasMessageContaining("data.merchantId")
                .hasMessageContaining("data.amount");

        assertThatThrownBy(() -> parser.parse(event("\"" + eventId + "\"", "null")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("data");
    }

    @Test
    void keepsAmountsExactWithoutGoingThroughDouble() {
        String big = "{\"paymentId\":\"" + paymentId + "\",\"amount\":12345678901234567.89,\"currency\":\"USD\",\"merchantId\":\"" + merchantId + "\"}";
        String small = "{\"paymentId\":\"" + paymentId + "\",\"amount\":75.50,\"currency\":\"USD\",\"merchantId\":\"" + merchantId + "\"}";

        assertThat(parser.parse(event("\"" + eventId + "\"", big)).data().getAmount())
                .isEqualTo(new java.math.BigDecimal("12345678901234567.89"));
        assertThat(parser.parse(event("\"" + eventId + "\"", small)).data().getAmount().toPlainString())
                .isEqualTo("75.50");
    }
}
