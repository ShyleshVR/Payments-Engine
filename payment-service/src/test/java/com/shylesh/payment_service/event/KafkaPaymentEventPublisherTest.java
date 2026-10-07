package com.shylesh.payment_service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.common.outbox.OutboxEvent;
import com.shylesh.payment_service.common.outbox.OutboxEventStatus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaPaymentEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @SuppressWarnings("unchecked")
    private JsonNode publishedData(String payload) {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .aggregateType("PAYMENT")
                .eventType("PAYMENT_COMPLETED")
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        new KafkaPaymentEventPublisher(kafkaTemplate, objectMapper).publish(outboxEvent);

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), eq(outboxEvent.getAggregateId().toString()), envelope.capture());
        return ((EventEnvelope<JsonNode>) envelope.getValue()).getData();
    }

    @Test
    void amountKeepsItsScaleOnTheWire() throws Exception {
        JsonNode data = publishedData("{\"amount\":75.50}");

        assertThat(objectMapper.writeValueAsString(data)).isEqualTo("{\"amount\":75.50}");
    }

    @Test
    void largeAmountsAreNotRoundedThroughDouble() {
        JsonNode data = publishedData("{\"amount\":12345678901234567.89}");

        assertThat(data.get("amount").decimalValue()).isEqualTo(new BigDecimal("12345678901234567.89"));
    }
}
