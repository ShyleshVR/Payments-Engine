package com.shylesh.payment_service.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class EventEnvelope<T> {

    private UUID eventId;
    private String eventType;
    private LocalDateTime occurredAt;
    private T data;
}