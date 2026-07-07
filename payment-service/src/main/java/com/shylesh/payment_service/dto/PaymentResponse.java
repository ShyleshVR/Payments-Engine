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
}