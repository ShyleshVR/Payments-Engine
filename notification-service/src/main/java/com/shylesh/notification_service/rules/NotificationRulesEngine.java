package com.shylesh.notification_service.rules;

import com.shylesh.notification_service.event.PaymentEventType;
import com.shylesh.notification_service.persistance.NotificationChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a payment event type to the channels a customer should be notified through.
 * Static for now — every event type notifies by EMAIL only. Once channel preference is
 * customer-specific, this becomes a lookup against stored preferences rather than a
 * fixed table, but the resolveChannels(String) contract stays the same for callers.
 */
@Component
public class NotificationRulesEngine {

    private static final Map<PaymentEventType, List<NotificationChannelType>> RULES =
            new EnumMap<>(PaymentEventType.class);

    static {
        RULES.put(PaymentEventType.PAYMENT_CREATED, List.of(NotificationChannelType.EMAIL));
        RULES.put(PaymentEventType.PAYMENT_COMPLETED, List.of(NotificationChannelType.EMAIL));
        RULES.put(PaymentEventType.PAYMENT_FAILED, List.of(NotificationChannelType.EMAIL));
        RULES.put(PaymentEventType.PAYMENT_REFUNDED, List.of(NotificationChannelType.EMAIL));
        // the customer's reserved funds were released
        RULES.put(PaymentEventType.PAYMENT_CANCELLED, List.of(NotificationChannelType.EMAIL));
        // PAYMENT_AUTHORIZED and PAYMENT_REFUND_FAILED concern the merchant (webhooks), not the customer
    }

    public List<NotificationChannelType> resolveChannels(String eventType) {
        try {
            return RULES.getOrDefault(PaymentEventType.valueOf(eventType), List.of());
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }
}
