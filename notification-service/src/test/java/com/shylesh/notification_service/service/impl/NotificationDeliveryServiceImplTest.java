package com.shylesh.notification_service.service.impl;

import com.shylesh.notification_service.channel.NotificationChannel;
import com.shylesh.notification_service.channel.NotificationChannelRegistry;
import com.shylesh.notification_service.channel.NotificationDeliveryException;
import com.shylesh.notification_service.persistance.*;
import com.shylesh.notification_service.retry.NotificationRetryPolicy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationDeliveryServiceImplTest {

    private NotificationRepository notificationRepository;
    private NotificationDeliveryAttemptRepository deliveryAttemptRepository;
    private NotificationChannelRegistry channelRegistry;
    private NotificationRetryPolicy retryPolicy;
    private PlatformTransactionManager transactionManager;
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
        transactionManager = mock(PlatformTransactionManager.class);
        emailChannel = mock(NotificationChannel.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new NotificationDeliveryServiceImpl(
                notificationRepository,
                deliveryAttemptRepository,
                channelRegistry,
                retryPolicy,
                new TransactionTemplate(transactionManager),
                meterRegistry
        );

        notification = notification(NotificationStatus.PENDING, 0);
        stubLookups(notification);
        when(channelRegistry.resolve(NotificationChannelType.EMAIL)).thenReturn(emailChannel);
    }

    private Notification notification(NotificationStatus status, int attemptCount) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .channel(NotificationChannelType.EMAIL)
                .status(status)
                .attemptCount(attemptCount)
                .nextAttemptAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void stubLookups(Notification n) {
        when(notificationRepository.lockIfDue(eq(n.getId()), any())).thenReturn(Optional.of(n));
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));
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
    }

    @Test
    void runsClaimAndRecordInSeparateTransactionsWithTheSendBetweenThem() throws NotificationDeliveryException {
        doNothing().when(emailChannel).send(any());

        service.attemptDelivery(notification.getId());

        // claim tx committed before the send, record tx committed after it
        var inOrder = inOrder(transactionManager, emailChannel);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(emailChannel).send(any());
        inOrder.verify(transactionManager).commit(any());
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
    }

    @Test
    void treatsUnexpectedRuntimeExceptionAsAFailedAttempt() throws NotificationDeliveryException {
        doThrow(new IllegalStateException("unexpected provider response")).when(emailChannel).send(any());
        when(retryPolicy.canRetry(1)).thenReturn(true);
        when(retryPolicy.nextAttemptAt(1)).thenReturn(LocalDateTime.now().plusSeconds(30));

        service.attemptDelivery(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getAttemptCount()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("unexpected provider response");
        verify(deliveryAttemptRepository).save(any());
    }

    @Test
    void truncatesErrorsLongerThanTheColumn() throws NotificationDeliveryException {
        String hugeError = "x".repeat(5000);
        doThrow(new RuntimeException(hugeError)).when(emailChannel).send(any());
        when(retryPolicy.canRetry(1)).thenReturn(true);
        when(retryPolicy.nextAttemptAt(1)).thenReturn(LocalDateTime.now().plusSeconds(30));

        service.attemptDelivery(notification.getId());

        assertThat(notification.getLastError()).hasSize(Notification.MAX_ERROR_LENGTH);

        ArgumentCaptor<NotificationDeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationDeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getErrorMessage()).hasSize(Notification.MAX_ERROR_LENGTH);
    }

    @Test
    void marksFailedWithoutPublishingInlineWhenRetriesExhausted() throws NotificationDeliveryException {
        notification = notification(NotificationStatus.RETRYING, 4);
        stubLookups(notification);

        doThrow(new NotificationDeliveryException("provider down")).when(emailChannel).send(any());
        when(retryPolicy.canRetry(5)).thenReturn(false);

        service.attemptDelivery(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttemptCount()).isEqualTo(5);
        // left for NotificationDeadLetterRelay to publish after commit
        assertThat(notification.getDltPublishedAt()).isNull();
    }

    @Test
    void doesNothingWhenNotificationCannotBeClaimed() {
        UUID claimedElsewhere = UUID.randomUUID();
        when(notificationRepository.lockIfDue(eq(claimedElsewhere), any())).thenReturn(Optional.empty());

        service.attemptDelivery(claimedElsewhere);

        verifyNoInteractions(channelRegistry, deliveryAttemptRepository);
    }

    @Test
    void discardsOutcomeIfAnotherDispatcherRecordedAnAttemptMeanwhile() throws NotificationDeliveryException {
        // Same notification, but another dispatcher already recorded attempt 1 after our lease expired.
        Notification takenOver = Notification.builder()
                .id(notification.getId())
                .eventId(notification.getEventId())
                .eventType(notification.getEventType())
                .paymentId(notification.getPaymentId())
                .customerId(notification.getCustomerId())
                .channel(NotificationChannelType.EMAIL)
                .status(NotificationStatus.RETRYING)
                .attemptCount(1)
                .build();
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(takenOver));
        doNothing().when(emailChannel).send(any());

        service.attemptDelivery(notification.getId());

        assertThat(takenOver.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(takenOver.getAttemptCount()).isEqualTo(1);
        verifyNoInteractions(deliveryAttemptRepository);
    }
}
