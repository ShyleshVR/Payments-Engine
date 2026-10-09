package com.shylesh.payment_service.common.config;

import com.shylesh.payment_service.event.Topics;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics owned by payment-service, created with explicit partition counts instead of relying on
 * broker auto-creation (which gives 1 partition, so only one consumer instance could ever work).
 * KafkaAdmin creates these at startup, or adds partitions to an existing topic that has fewer.
 * Partition count bounds consumer parallelism: with N partitions, up to N instances of a
 * consumer group share the work.
 */
@Configuration
public class KafkaTopicsConfig {

    @Value("${payflow.kafka.partitions:3}")
    private int partitions;

    @Value("${payflow.kafka.replication-factor:1}")
    private short replicationFactor;

    /** Payment lifecycle events, keyed by payment id (per-payment order within a partition). */
    @Bean
    public NewTopic paymentCreatedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_CREATED).partitions(partitions).replicas(replicationFactor).build();
    }

    /** Consumers' dead letters for payment events; same partition count as the source topic. */
    @Bean
    public NewTopic paymentCreatedDeadLetterTopic() {
        return TopicBuilder.name(Topics.PAYMENT_CREATED_DLT).partitions(partitions).replicas(replicationFactor).build();
    }

    /** Saga commands to the ledger, keyed by payment id (a payment's commands stay in order). */
    @Bean
    public NewTopic ledgerCommandsTopic() {
        return TopicBuilder.name(Topics.LEDGER_COMMANDS).partitions(partitions).replicas(replicationFactor).build();
    }

    /** ledger-service's dead letters for commands; same partition count as the source topic. */
    @Bean
    public NewTopic ledgerCommandsDeadLetterTopic() {
        return TopicBuilder.name(Topics.LEDGER_COMMANDS_DLT).partitions(partitions).replicas(replicationFactor).build();
    }

}
