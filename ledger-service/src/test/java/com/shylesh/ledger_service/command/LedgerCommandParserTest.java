package com.shylesh.ledger_service.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerCommandParserTest {

    private final LedgerCommandParser parser = new LedgerCommandParser(new ObjectMapper().registerModule(new JavaTimeModule()));

    private static String message(String data) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"HOLD_REFUND\",\"occurredAt\":\"2026-10-08T10:00:00\",\"data\":" + data + "}";
    }

    private static String data(String commandType, String amount) {
        return "{\"commandId\":\"" + UUID.randomUUID() + "\",\"commandType\":\"" + commandType + "\",\"sagaId\":\"" + UUID.randomUUID()
                + "\",\"paymentId\":\"" + UUID.randomUUID() + "\",\"merchantId\":\"" + UUID.randomUUID()
                + "\",\"amount\":" + amount + ",\"currency\":\"USD\"}";
    }

    @Test
    void parsesTheAmountExactly() {
        LedgerCommand command = parser.parse(message(data("HOLD_REFUND", "12345678901234.5678")));

        assertThat(command.getAmount()).isEqualByComparingTo("12345678901234.5678");
        assertThat(command.getCommandType()).isEqualTo("HOLD_REFUND");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void rejectsUnknownTypesAndNonPositiveAmounts() {
        assertThatThrownBy(() -> parser.parse(message(data("PAY_EVERYONE", "1.00"))))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("unknown commandType");
        assertThatThrownBy(() -> parser.parse(message(data("HOLD_REFUND", "0"))))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("amount");
    }

    @Test
    void rejectsMissingIds() {
        assertThatThrownBy(() -> parser.parse(message("{\"commandType\":\"SETTLE_PAYMENT\",\"amount\":1,\"currency\":\"USD\"}")))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("commandId missing")
                .hasMessageContaining("sagaId missing");
        assertThatThrownBy(() -> parser.parse("{\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(InvalidCommandException.class);
    }
}
