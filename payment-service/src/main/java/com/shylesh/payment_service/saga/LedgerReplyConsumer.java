package com.shylesh.payment_service.saga;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.common.kafka.InvalidMessageException;
import com.shylesh.payment_service.event.Topics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The ledger's replies to saga commands. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerReplyConsumer {

    private final ObjectMapper objectMapper;
    private final SagaOrchestrator orchestrator;

    @KafkaListener(topics = Topics.LEDGER_REPLIES)
    public void consume(String message) {
        LedgerReplyMessage reply = parse(message);
        log.info("Received ledger reply. sagaId={}, commandId={}, type={}, outcome={}",
                reply.sagaId(), reply.commandId(), reply.commandType(), reply.outcome());
        orchestrator.onLedgerReply(reply);
    }

    private LedgerReplyMessage parse(String message) {
        LedgerReplyMessage reply;
        try {
            JsonNode data = objectMapper.readTree(message).get("data");
            if (data == null || data.isNull()) {
                throw new InvalidMessageException("Ledger reply without data");
            }
            reply = objectMapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .treeToValue(data, LedgerReplyMessage.class);
        } catch (InvalidMessageException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidMessageException("Malformed ledger reply: " + e.getMessage(), e);
        }
        if (reply.sagaId() == null || reply.commandId() == null || reply.outcome() == null) {
            throw new InvalidMessageException("Ledger reply missing sagaId, commandId or outcome");
        }
        return reply;
    }
}
