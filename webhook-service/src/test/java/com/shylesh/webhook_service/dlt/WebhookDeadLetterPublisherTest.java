package com.shylesh.webhook_service.dlt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookDeadLetterPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private WebhookDeadLetterPublisher publisher;
    private WebhookDelivery delivery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new WebhookDeadLetterPublisher(
                kafkaTemplate,
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofMillis(200)
        );
        delivery = WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_FAILED")
                .paymentId(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .url("https://merchant.example.com/hooks")
                .payload("{}")
                .status(WebhookDeliveryStatus.FAILED)
                .attemptCount(5)
                .lastError("HTTP 500")
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsNormallyOnceKafkaAcknowledges() throws DeadLetterPublishException {
        SendResult<String, String> ack = mock(SendResult.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(ack));

        publisher.publish(delivery);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq(WebhookTopics.WEBHOOK_DEAD_LETTER),
                eq(delivery.getPaymentId().toString()),
                payload.capture()
        );
        assertThat(payload.getValue())
                .contains(delivery.getId().toString())
                .contains(delivery.getEventId().toString())
                .contains("HTTP 500");
    }

    @Test
    void throwsWhenKafkaRejectsTheSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        assertThatThrownBy(() -> publisher.publish(delivery))
                .isInstanceOf(DeadLetterPublishException.class)
                .hasMessageContaining("broker unavailable");
    }

    @Test
    void throwsWhenKafkaDoesNotConfirmInTime() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>()); // never completes

        assertThatThrownBy(() -> publisher.publish(delivery))
                .isInstanceOf(DeadLetterPublishException.class);
    }

    @Test
    void throwsWhenSendFailsSynchronously() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("metadata not available"));

        assertThatThrownBy(() -> publisher.publish(delivery))
                .isInstanceOf(DeadLetterPublishException.class)
                .hasMessageContaining("metadata not available");
    }
}
