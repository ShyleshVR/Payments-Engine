package com.shylesh.payout_service.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payout_service.config.Topics;
import com.shylesh.payout_service.outbox.OutboxEvent;
import com.shylesh.payout_service.outbox.OutboxEventStatus;
import com.shylesh.payout_service.payout.Payout;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the outbox rows of the payout saga, in PayFlow's wire shape {eventId, eventType,
 * occurredAt, data}: commands to the ledger and payout events for the rest of the system. Both
 * are keyed by payout id, so one payout's messages stay in order.
 */
@Component
@RequiredArgsConstructor
public class OutboxMessages {

    private final ObjectMapper objectMapper;

    /** A ledger command; re-sends reuse the command id, so the ledger handles it once. */
    public OutboxEvent ledgerCommand(PayoutSaga saga, Payout payout, LedgerCommandType type, UUID commandId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commandId", commandId);
        data.put("commandType", type.name());
        data.put("sagaId", saga.getId());
        data.put("payoutId", payout.getId());
        data.put("merchantId", payout.getMerchantId());
        data.put("amount", payout.getAmount());
        data.put("currency", payout.getCurrency());
        if (type == LedgerCommandType.HOLD_PAYOUT) {
            data.put("cutoff", payout.getCutoff());
        }
        return build(payout.getId(), Topics.LEDGER_COMMANDS, type.name(), data, saga.getTraceParent());
    }

    /** PAYOUT_CREATED, PAYOUT_PAID, PAYOUT_FAILED or PAYOUT_RETURNED, with the payout as it is now. */
    public OutboxEvent payoutEvent(Payout payout, String eventType, String traceParent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payoutId", payout.getId());
        data.put("merchantId", payout.getMerchantId());
        data.put("amount", payout.getAmount());
        data.put("currency", payout.getCurrency());
        data.put("status", payout.getStatus().name());
        data.put("trigger", payout.getTrigger().name());
        data.put("failureCode", payout.getFailureCode());
        data.put("transferId", payout.getTransferId());
        return build(payout.getId(), Topics.PAYOUT_EVENTS, eventType, data, traceParent);
    }

    private OutboxEvent build(UUID aggregateId, String topic, String eventType, Map<String, Object> data, String traceParent) {
        UUID messageId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", messageId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", now);
        envelope.put("data", data);
        try {
            return OutboxEvent.builder()
                    .id(messageId)
                    .aggregateId(aggregateId)
                    .topic(topic)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(envelope))
                    .status(OutboxEventStatus.PENDING)
                    .traceParent(traceParent)
                    .createdAt(now)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable outbox message " + eventType, e);
        }
    }
}
