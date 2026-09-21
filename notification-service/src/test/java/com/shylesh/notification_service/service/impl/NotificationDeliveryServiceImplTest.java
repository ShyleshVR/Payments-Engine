package com.shylesh.notification_service.service.impl;

import com.shylesh.notification_service.channel.NotificationChannel;
import com.shylesh.notification_service.channel.NotificationChannelRegistry;
import com.shylesh.notification_service.channel.NotificationDeliveryException;
import com.shylesh.notification_service.dlt.NotificationDeadLetterPublisher;
import com.shylesh.notification_service.persistance.*;
import com.shylesh.notification_service.retry.NotificationRetryPolicy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDeliveryServiceImplTest {

    private NotificationRepository notificationRepository;
    private NotificationDeliveryAttemptRepository deliveryAttemptRepository;
    private NotificationChannelRegistry channelRegistry;
    private NotificationRetryPolicy retryPolicy;
    private NotificationDeadLetterPublisher deadLetterPublisher;
    private NotificationChannel emailChannel;
    private MeterRegistry meterRegistry;
    private NotificationDeliveryServiceImpl service;

    private Notification notification;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        deliveryAttemptRepository = mock(NotificationDeliveryAttemptRepository.class);
        channelRegistry = mock(NotificationChannelRegistry.class);
        retryPolicy = mock(NotificationRetryPolicy.class);
        deadLetterPublisher = mock(NotificationDeadLetterPublisher.class);
        emailChannel = mock(NotificationChannel.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new NotificationDeliveryServiceImpl(
                notificationRepository,
                deliveryAttemptRepository,
                channelRegistry,
                retryPolicy,
                deadLetterPublisher,
                meterRegistry
        );

        notification = Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.PENDING)
                .attemptCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(notificationRepository.findById(notification.getId())).thenReturn(java.util.Optional.of(notification));
        when(channelRegistry.resolve(NotificationChannelType.EMAIL)).thenReturn(emailChannel);
    }

    @Test
    void marksSentAndRecordsSuccessOnSuccessfulSend() throws NotificationDeliveryException {
        doNothing().when(emailChannel).send(any());

        service.attemptDelivery(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttemptCount()).isEqualTo(1);

        ArgumentCaptor<NotificationDeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationDeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(attemptCaptor.getValue().getAttemptNumber()).isEqualTo(1);

        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void marksRetryingWithBackoffOnFailureWhenRetriesRemain() throws NotificationDeliveryException {
        doThrow(new NotificationDeliveryException("provider timeout")).when(emailChannel).send(any());
        when(retryPolicy.canRetry(1)).thenReturn(true);
        LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(30);
        when(retryPolicy.nextAttemptAt(1)).thenReturn(nextAttempt);

        service.attemptDelivery(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getAttemptCount()).isEqualTo(1);
        assertThat(notification.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(notification.getLastError()).contains("provider timeout");

        ArgumentCaptor<NotificationDeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationDeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(DeliveryAttemptStatus.FAILURE);

        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void marksFailedAndPublishesToDltWhenRetriesExhausted() throws NotificationDeliveryException {
        notification = Notification.builder()
                .id(notification.getId())
                .eventId(notification.getEventId())
                .eventType(notification.getEventType())
                .paymentId(notification.getPaymentId())
                .customerId(notification.getCustomerId())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.RETRYING)
                .attemptCount(4)
                .build();
        when(notificationRepository.findById(notification.getId())).thenReturn(java.util.Optional.of(notification));

        doThrow(new NotificationDeliveryException("provider down")).when(emailChannel).send(any());
        when(retryPolicy.canRetry(5)).thenReturn(false);

        service.attemptDelivery(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttemptCount()).isEqualTo(5);

        verify(deadLetterPublisher).publish(notification, "provider down");
    }

    @Test
    void doesNothingWhenNotificationNoLongerExists() {
        UUID missingId = UUID.randomUUID();
        when(notificationRepository.findById(missingId)).thenReturn(java.util.Optional.empty());

        service.attemptDelivery(missingId);

        verifyNoInteractions(channelRegistry, deliveryAttemptRepository, deadLetterPublisher);
    }
}
