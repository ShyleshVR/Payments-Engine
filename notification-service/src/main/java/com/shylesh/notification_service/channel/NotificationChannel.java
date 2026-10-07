package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;

/**
 * Contract for implementations:
 * - send() runs outside any DB transaction, under a 2-minute lease
 *   (NotificationDeliveryServiceImpl.LEASE). A real provider client must bound its own call
 *   time well below that (connect/read timeouts), or a slow send could be re-claimed and sent twice.
 * - Throw NotificationDeliveryException for failures worth retrying, and
 *   NotificationDeliveryException.permanent(...) for ones that will never succeed.
 */
public interface NotificationChannel {

    NotificationChannelType getType();

    void send(NotificationContext context) throws NotificationDeliveryException;
}
