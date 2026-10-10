package com.shylesh.payout_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics this service writes to, created with explicit partition counts. ledger-commands is also
 * declared by payment-service; declaring it here too means either orchestrator can start first.
 */
@Configuration
public class KafkaTopicsConfig {

    @Value("${payflow.kafka.partitions:3}")
    private int partitions;

    @Value("${payflow.kafka.replication-factor:1}")
    private int replicationFactor;

    @Bean
    public NewTopic payoutEventsTopic() {
        return TopicBuilder.name(Topics.PAYOUT_EVENTS).partitions(partitions).replicas(replicationFactor).build();
    }

    /** webhook-service's dead letters for payout events. */
    @Bean
    public NewTopic payoutEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.PAYOUT_EVENTS_DLT).partitions(partitions).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic ledgerCommandsTopic() {
        return TopicBuilder.name(Topics.LEDGER_COMMANDS).partitions(partitions).replicas(replicationFactor).build();
    }
}
