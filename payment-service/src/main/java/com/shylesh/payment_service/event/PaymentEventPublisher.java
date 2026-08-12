package com.shylesh.payment_service.event;

import com.shylesh.payment_service.common.outbox.OutboxEvent;

import org.springframework.kafka.support.SendResult;
import java.util.concurrent.CompletableFuture;

public interface PaymentEventPublisher {

    CompletableFuture<SendResult<String, String>> publish(
            OutboxEvent outboxEvent
    );
}
