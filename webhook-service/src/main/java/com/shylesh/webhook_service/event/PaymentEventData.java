package com.shylesh.webhook_service.event;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Common payload shape shared by payment lifecycle events today. Only PAYMENT_CREATED is
 * produced by payment-service so far; when the other event types are wired up, fields that
 * turn out not to be common (e.g. a failure reason on PAYMENT_FAILED) belong on their own
 * type rather than bolted on here.
 */
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
