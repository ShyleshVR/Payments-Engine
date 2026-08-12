package com.shylesh.payment_service.event;

import com.shylesh.payment_service.entity.Payment;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher
        implements PaymentEventPublisher {

    private final KafkaTemplate<String, PaymentCreatedEvent> kafkaTemplate;

    @Override
    public void publishPaymentCreated(Payment payment) {

        PaymentCreatedEvent event =
                PaymentCreatedEvent.builder()
                        .paymentId(payment.getId())
                        .amount(payment.getAmount())
                        .currency(payment.getCurrency())
                        .merchantId(payment.getMerchantId())
                        .customerId(payment.getCustomerId())
                        .createdAt(payment.getCreatedAt())
                        .build();

        kafkaTemplate.send(
                Topics.PAYMENT_CREATED,
                payment.getId().toString(),
                event
        );
    }
}
