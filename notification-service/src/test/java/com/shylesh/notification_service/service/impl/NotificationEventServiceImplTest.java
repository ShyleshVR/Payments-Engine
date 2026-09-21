package com.shylesh.notification_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.notification_service.event.EventEnvelope;
import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationChannelType;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.persistance.ProcessedEventRepository;
import com.shylesh.notification_service.rules.NotificationRulesEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationEventServiceImplTest {

    private ProcessedEventRepository processedEventRepository;
    private NotificationRepository notificationRepository;
    private NotificationRulesEngine rulesEngine;
    private NotificationEventServiceImpl service;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        rulesEngine = mock(NotificationRulesEngine.class);

        service = new NotificationEventServiceImpl(
                processedEventRepository,
                notificationRepository,
                rulesEngine,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    private EventEnvelope envelope(UUID eventId, String eventType, UUID paymentId, UUID customerId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, Object> data = Map.of(
                "paymentId", paymentId.toString(),
                "amount", "100.00",
                "currency", "USD",
                "merchantId", UUID.randomUUID().toString(),
                "customerId", customerId.toString(),
                "createdAt", LocalDateTime.now().toString()
        );
        return new EventEnvelope(eventId, eventType, LocalDateTime.now(), mapper.valueToTree(data));
    }

    @Test
    void createsOneNotificationPerRuleResolvedChannel() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(rulesEngine.resolveChannels("PAYMENT_CREATED"))
                .thenReturn(List.of(NotificationChannelType.EMAIL));

        service.handle(envelope(eventId, "PAYMENT_CREATED", paymentId, customerId));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getCustomerId()).isEqualTo(customerId);
        assertThat(saved.getChannel()).isEqualTo(NotificationChannelType.EMAIL);

        verify(processedEventRepository, times(1)).save(any());
    }

    @Test
    void skipsAlreadyProcessedEventWithoutCreatingNotifications() {
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.handle(envelope(eventId, "PAYMENT_CREATED", UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(notificationRepository);
        verify(processedEventRepository, never()).save(any());
        verifyNoInteractions(rulesEngine);
    }

    @Test
    void marksEventProcessedEvenWhenNoChannelsAreConfigured() {
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(rulesEngine.resolveChannels("PAYMENT_DISPUTED")).thenReturn(List.of());

        service.handle(envelope(eventId, "PAYMENT_DISPUTED", UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(notificationRepository);
        verify(processedEventRepository, times(1)).save(any());
    }
}
