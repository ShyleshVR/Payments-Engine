package com.shylesh.notification_service.dlt;

import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationChannelType;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.persistance.NotificationStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDeadLetterRelayTest {

    private NotificationRepository notificationRepository;
    private NotificationDeadLetterPublisher publisher;
    private PlatformTransactionManager transactionManager;
    private NotificationDeadLetterRelay relay;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        publisher = mock(NotificationDeadLetterPublisher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        relay = new NotificationDeadLetterRelay(notificationRepository, publisher, new TransactionTemplate(transactionManager));
    }

    private Notification failedNotification() {
        return Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.FAILED)
                .attemptCount(5)
                .build();
    }

    @Test
    void marksDeadLetterPublishedOnlyAfterKafkaAcks() {
        Notification first = failedNotification();
        Notification second = failedNotification();
        when(notificationRepository.lockNextUnpublishedDeadLetter())
                .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());

        relay.relayDeadLetters();

        verify(publisher).publish(first);
        verify(publisher).publish(second);
        assertThat(first.getDltPublishedAt()).isNotNull();
        assertThat(second.getDltPublishedAt()).isNotNull();
    }

    @Test
    void leavesNotificationUnmarkedAndRollsBackWhenPublishFails() {
        Notification failed = failedNotification();
        when(notificationRepository.lockNextUnpublishedDeadLetter()).thenReturn(Optional.of(failed));
        doThrow(new DeadLetterPublishException("broker down", null)).when(publisher).publish(failed);

        relay.relayDeadLetters();

        assertThat(failed.getDltPublishedAt()).isNull();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        // stops the batch rather than hammering a broker that is down
        verify(notificationRepository, times(1)).lockNextUnpublishedDeadLetter();
    }
}
