package com.shylesh.notification_service.channel;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything a channel needs to compose and send a message, independent of how the
 * notification is persisted or which event triggered it.
 */
public record NotificationContext(
        UUID notificationId,
        UUID paymentId,
        UUID customerId,
        String eventType,
        BigDecimal amount,
        String currency
) {
}
