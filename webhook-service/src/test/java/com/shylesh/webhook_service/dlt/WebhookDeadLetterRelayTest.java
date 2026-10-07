package com.shylesh.webhook_service.dlt;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookDeadLetterRelayTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookDeadLetterPublisher publisher;
    private PlatformTransactionManager transactionManager;
    private WebhookDeadLetterRelay relay;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        publisher = mock(WebhookDeadLetterPublisher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        relay = new WebhookDeadLetterRelay(deliveryRepository, publisher, new TransactionTemplate(transactionManager));
    }

    private WebhookDelivery failed() {
        return WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_COMPLETED")
                .merchantId(UUID.randomUUID())
                .status(WebhookDeliveryStatus.FAILED)
                .attemptCount(5)
                .build();
    }

    @Test
    void marksEachDeadLetterOnlyAfterKafkaAcks() {
        WebhookDelivery first = failed();
        WebhookDelivery second = failed();
        when(deliveryRepository.lockNextUnpublishedDeadLetter())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());

        relay.relayDeadLetters();

        verify(publisher).publish(first);
        verify(publisher).publish(second);
        assertThat(first.getDltPublishedAt()).isNotNull();
        assertThat(second.getDltPublishedAt()).isNotNull();
    }

    @Test
    void publishFailureRollsBackLeavesTheRowUnmarkedAndStopsTheBatch() {
        WebhookDelivery delivery = failed();
        when(deliveryRepository.lockNextUnpublishedDeadLetter()).thenReturn(Optional.of(delivery));
        doThrow(new DeadLetterPublishException("broker down", null)).when(publisher).publish(delivery);

        relay.relayDeadLetters();

        assertThat(delivery.getDltPublishedAt()).isNull();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(deliveryRepository, times(1)).lockNextUnpublishedDeadLetter();
    }
}
