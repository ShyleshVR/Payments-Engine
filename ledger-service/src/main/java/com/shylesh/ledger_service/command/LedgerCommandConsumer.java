package com.shylesh.ledger_service.command;

import com.shylesh.ledger_service.config.LedgerTopics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Commands from payment-service's saga orchestrator. Invalid messages throw
 * InvalidCommandException (straight to the DLT); everything else thrown by the handler is
 * classified by KafkaConsumerConfig's error handler (infrastructure failures retried until they
 * pass, so no command is skipped).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerCommandConsumer {

    private final LedgerCommandParser parser;
    private final LedgerCommandHandler handler;

    @KafkaListener(topics = LedgerTopics.COMMANDS)
    public void consume(String message) {
        LedgerCommand command = parser.parse(message);
        log.info("Received ledger command. commandId={}, type={}, paymentId={}",
                command.getCommandId(), command.getCommandType(), command.getPaymentId());
        handler.handle(command);
    }
}
