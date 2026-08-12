package com.shylesh.payment_service.event;

import com.shylesh.payment_service.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventFactory {

    public PaymentCreatedEvent create(Payment payment) {
        return PaymentCreatedEvent.builder()
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .merchantId(payment.getMerchantId())
                .customerId(payment.getCustomerId())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}