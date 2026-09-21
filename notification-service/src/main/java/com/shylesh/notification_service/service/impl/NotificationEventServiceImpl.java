package com.shylesh.notification_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.notification_service.event.EventEnvelope;
import com.shylesh.notification_service.event.PaymentEventData;
import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationChannelType;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.persistance.NotificationStatus;
import com.shylesh.notification_service.persistance.ProcessedEvent;
import com.shylesh.notification_service.persistance.ProcessedEventRepository;
import com.shylesh.notification_service.rules.NotificationRulesEngine;
import com.shylesh.notification_service.service.NotificationEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventServiceImpl implements NotificationEventService {

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRulesEngine rulesEngine;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void handle(EventEnvelope envelope) {

        if (processedEventRepository.existsById(envelope.getEventId())) {
            log.info(
                    "Skipping already-processed event. eventId={}, eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType()
            );
            return;
        }

        PaymentEventData data = objectMapper.convertValue(envelope.getData(), PaymentEventData.class);

        List<NotificationChannelType> channels = rulesEngine.resolveChannels(envelope.getEventType());

        LocalDateTime now = LocalDateTime.now();

        for (NotificationChannelType channel : channels) {

            Notification notification = Notification.builder()
                    .id(UUID.randomUUID())
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .paymentId(data.getPaymentId())
                    .customerId(data.getCustomerId())
                    .channel(channel)
                    .status(NotificationStatus.PENDING)
                    .nextAttemptAt(now)
                    .build();

            notificationRepository.save(notification);
        }

        processedEventRepository.save(
                new ProcessedEvent(envelope.getEventId(), envelope.getEventType(), now)
        );

        log.info(
                "Processed event. eventId={}, eventType={}, notificationsCreated={}",
                envelope.getEventId(),
                envelope.getEventType(),
                channels.size()
        );
    }
}
