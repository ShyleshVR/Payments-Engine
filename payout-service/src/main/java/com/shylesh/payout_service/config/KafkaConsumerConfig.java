package com.shylesh.payout_service.config;

import com.shylesh.payout_service.saga.InvalidMessageException;

import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Three kinds of consumer failure, three behaviours:
 * - invalid message (InvalidMessageException): dead-lettered immediately, retrying can't fix it;
 * - infrastructure down (see ConsumerFailureClassifier): retried with backoff capped at 60s,
 *   for as long as the outage lasts. The partition is held meanwhile, which is the point: no
 *   valid reply is skipped (the saga would wait for a re-sent command's reply instead), and
 *   consumer lag makes the outage visible;
 * - anything else: a few quick retries, then the DLT.
 * Each backoff step (60s max) plus a DB connection timeout (30s) stays under the consumer's
 * max.poll.interval.ms (5 min), so retrying never gets the consumer kicked from the group.
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
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
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
