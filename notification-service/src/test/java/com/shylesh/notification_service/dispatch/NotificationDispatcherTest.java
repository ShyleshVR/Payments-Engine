package com.shylesh.notification_service.dispatch;

import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationChannelType;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.persistance.NotificationStatus;
import com.shylesh.notification_service.service.NotificationDeliveryService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDispatcherTest {

    @Test
    void continuesDispatchingRemainingNotificationsWhenOneFails() {
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);

        Notification first = Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.PENDING)
                .build();
        Notification second = Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.PENDING)
                .build();

        when(notificationRepository.findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(any(), any()))
                .thenReturn(List.of(first, second));
        doThrow(new RuntimeException("boom")).when(deliveryService).attemptDelivery(first.getId());

        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationRepository, deliveryService);
        dispatcher.dispatchDueNotifications();

        verify(deliveryService).attemptDelivery(first.getId());
        verify(deliveryService).attemptDelivery(second.getId());
    }
}
