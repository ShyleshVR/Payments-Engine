package com.shylesh.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventParserTest {

    private final PaymentEventParser parser = new PaymentEventParser(new ObjectMapper().findAndRegisterModules());

    private final String eventId = UUID.randomUUID().toString();
    private final String paymentId = UUID.randomUUID().toString();

    private String event(String eventIdJson, String data) {
        return "{\"eventId\":" + eventIdJson + ",\"eventType\":\"PAYMENT_COMPLETED\","
                + "\"occurredAt\":\"2026-10-05T08:00:00\",\"data\":" + data + "}";
    }

    @Test
    void parsesAValidEventWithoutACustomer() {
        EventEnvelope event = parser.parse(event("\"" + eventId + "\"",
                "{\"paymentId\":\"" + paymentId + "\",\"amount\":75.50,\"currency\":\"USD\"}"));

        assertThat(event.getEventId()).hasToString(eventId);
        assertThat(event.getData().getPaymentId()).hasToString(paymentId);
        assertThat(event.getData().getCustomerId()).isNull();
        assertThat(event.getData().getAmount().toPlainString()).isEqualTo("75.50");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Malformed payment event");
    }

    @Test
    void rejectsNullEventIdBeforeAnyDatabaseWork() {
        assertThatThrownBy(() -> parser.parse(event("null", "{\"paymentId\":\"" + paymentId + "\"}")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsMissingOrNullDataAndMissingPaymentId() {
        assertThatThrownBy(() -> parser.parse(event("\"" + eventId + "\"", "null")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("data");
        assertThatThrownBy(() -> parser.parse(event("\"" + eventId + "\"", "{\"currency\":\"USD\"}")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("data.paymentId");
    }
}
