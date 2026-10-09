package com.shylesh.ledger_service.command;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape of every PayFlow Kafka message ({eventId, eventType, occurredAt, data}). For commands,
 * eventId is the message id (new on each re-send) and eventType the command type.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CommandEnvelope {

    private UUID eventId;
    private String eventType;
    private LocalDateTime occurredAt;
    private LedgerCommand data;
}
