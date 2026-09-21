package com.shylesh.payment_service.mapper;

import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;

import org.springframework.stereotype.Component;

@Component
public class PaymentMapperImpl implements PaymentMapper {

    @Override
    public PaymentResponse toResponse(Payment payment) {
        if (payment == null) {
            return null;
        }

        return PaymentResponse.builder()
                .paymentId("pay_" + payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
