package com.shylesh.payment_service.event;

import com.shylesh.payment_service.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public CompletableFuture<SendResult<String, String>> publish(
            OutboxEvent outboxEvent) {

        return kafkaTemplate.send(
                Topics.PAYMENT_CREATED,
                outboxEvent.getAggregateId().toString(),
                outboxEvent.getPayload()
        );
    }
}
