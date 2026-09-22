package com.shylesh.ledger_service.event;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class PaymentEventData {

    private UUID paymentId;

    private BigDecimal amount;

    private String currency;

    private UUID merchantId;

    private UUID customerId;

    private LocalDateTime createdAt;
}
