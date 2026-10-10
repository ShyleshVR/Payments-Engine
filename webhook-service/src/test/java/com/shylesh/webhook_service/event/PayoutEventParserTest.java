package com.shylesh.webhook_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutEventParserTest {

    private final PayoutEventParser parser = new PayoutEventParser(new ObjectMapper().findAndRegisterModules());

    private final String payoutId = UUID.randomUUID().toString();
    private final String merchantId = UUID.randomUUID().toString();

    private static String event(String data) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PAYOUT_PAID\","
                + "\"occurredAt\":\"2026-10-09T08:00:00\",\"data\":" + data + "}";
    }

    @Test
    void parsesAValidEventWithAnExactAmount() {
        PayoutEvent event = parser.parse(event("{\"payoutId\":\"" + payoutId + "\",\"merchantId\":\"" + merchantId
                + "\",\"amount\":1234567890.10,\"currency\":\"USD\",\"status\":\"PAID\",\"trigger\":\"BATCH\",\"transferId\":\"tr_1\"}"));

        assertThat(event.data().getPayoutId()).hasToString(payoutId);
        assertThat(event.data().getAmount()).isEqualByComparingTo("1234567890.10");
        assertThat(event.data().getAmount().scale()).isEqualTo(2);
        assertThat(event.data().getStatus()).isEqualTo("PAID");
    }

    @Test
    void rejectsMissingFieldsAndMalformedJson() {
        assertThatThrownBy(() -> parser.parse(event("{\"merchantId\":\"" + merchantId + "\",\"currency\":\"USD\"}")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("data.payoutId")
                .hasMessageContaining("data.amount");
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(InvalidEventException.class);
    }
}
