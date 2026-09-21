package com.shylesh.payment_service.mapper;

import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;

public interface PaymentMapper {

    PaymentResponse toResponse(Payment payment);
}
