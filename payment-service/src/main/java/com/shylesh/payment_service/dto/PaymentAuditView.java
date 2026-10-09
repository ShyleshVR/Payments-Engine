package com.shylesh.payment_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A payment as the reconciliation sees it.
 *
 * @param sagaActive      true while a payment or refund saga is still running: its money movements
 *                        may legitimately be incomplete
 * @param processorBacked false for payments made before the card processor integration (no
 *                        payment method): the processor has no record of them
 */
public record PaymentAuditView(
        String paymentId,
        String status,
        BigDecimal amount,
        String currency,
        String captureMethod,
        String failureCode,
        String refundFailureCode,
        boolean sagaActive,
        boolean processorBacked,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
