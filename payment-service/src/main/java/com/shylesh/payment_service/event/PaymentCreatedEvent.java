package com.shylesh.payment_service.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentCreatedEvent {

    private UUID paymentId;

    private BigDecimal amount;

    private String currency;

    private UUID merchantId;

    private UUID customerId;

    private LocalDateTime createdAt;
}
