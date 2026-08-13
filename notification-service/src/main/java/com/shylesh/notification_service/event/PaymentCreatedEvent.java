package com.shylesh.notification_service.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCreatedEvent {

    private UUID paymentId;

    private BigDecimal amount;

    private String currency;

    private UUID merchantId;

    private UUID customerId;

    private LocalDateTime createdAt;
}