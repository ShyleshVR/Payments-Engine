package com.shylesh.payment_service.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class PaymentResponse {

    private String paymentId;

    private BigDecimal amount;

    private String currency;

    private String status;

    private LocalDateTime createdAt;

    private String captureMethod;

    /** FAILED / CANCELLED: why (processor decline code, processor_unavailable, authorization_expired). */
    private String failureCode;

    /** Why the last refund attempt failed (the payment stays SUCCESS and can be refunded again). */
    private String refundFailureCode;
}