package com.shylesh.ledger_service.command;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a Kafka message into a validated command, or fails with InvalidCommandException (dead-
 * lettered immediately: retrying can't fix it) before anything is posted.
 */
@Component
@RequiredArgsConstructor
public class LedgerCommandParser {

    private final ObjectMapper objectMapper;

    public LedgerCommand parse(String message) {
        CommandEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, CommandEnvelope.class);
        } catch (Exception e) {
            throw new InvalidCommandException("Malformed ledger command: " + e.getMessage(), e);
        }

        LedgerCommand command = envelope.getData();
        List<String> problems = new ArrayList<>();
        if (command == null) {
            throw new InvalidCommandException("Ledger command without data. eventId=" + envelope.getEventId());
        }
        if (command.getCommandId() == null) problems.add("commandId missing");
        if (command.getSagaId() == null) problems.add("sagaId missing");
        if (command.getPaymentId() == null) problems.add("paymentId missing");
        if (command.getMerchantId() == null) problems.add("merchantId missing");
        if (command.getCurrency() == null || !command.getCurrency().matches("[A-Z]{3}")) problems.add("currency invalid");
        if (command.getAmount() == null || command.getAmount().compareTo(BigDecimal.ZERO) <= 0) problems.add("amount must be positive");
        if (command.getCommandType() == null || Arrays.stream(LedgerCommandType.values())
                .noneMatch(type -> type.name().equals(command.getCommandType()))) {
            problems.add("unknown commandType " + command.getCommandType());
        }

        if (!problems.isEmpty()) {
            throw new InvalidCommandException("Invalid ledger command " + problems + ". commandId=" + command.getCommandId());
        }
        return command;
    }
}
