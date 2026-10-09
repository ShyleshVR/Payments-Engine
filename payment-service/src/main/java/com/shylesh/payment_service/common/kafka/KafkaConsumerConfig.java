package com.shylesh.payment_service.common.kafka;

import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer failure handling, as in the other services:
 * - invalid message (InvalidMessageException): dead-lettered immediately, retrying can't fix it;
 * - infrastructure down (ConsumerFailureClassifier): retried with backoff capped at 60s for as
 *   long as the outage lasts, holding the partition, so no ledger reply is ever skipped (a lost
 *   reply would only be recovered by the saga's re-send timer, much later);
 * - anything else: a few quick retries, then the DLT.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    static BackOff unboundedInfrastructureBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(60_000L);
        return backOff;
    }

    static BackOff boundedBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        return backOff;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties) {
        // Dead letters keep the original message as a string: the application's template
        // serializes values as JSON, which would wrap it in quotes. Deliberately not a bean, so
        // Spring Boot still auto-configures that main template.
        KafkaTemplate<String, String> deadLetterKafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null), new StringSerializer(), new StringSerializer()));
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> {
                    log.error("Dead-lettering record from topic {} offset {}", record.topic(), record.offset(), exception);
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                }
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, boundedBackOff());
        BackOff infrastructureBackOff = unboundedInfrastructureBackOff();
        errorHandler.setBackOffFunction((record, exception) ->
                ConsumerFailureClassifier.isTransientInfrastructureFailure(exception) ? infrastructureBackOff : null);
        errorHandler.addNotRetryableExceptions(InvalidMessageException.class);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
