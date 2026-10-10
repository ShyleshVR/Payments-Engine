package com.shylesh.ledger_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics owned by ledger-service, created with explicit partition counts instead of relying on
 * broker auto-creation. KafkaAdmin creates these at startup, or adds partitions to an existing
 * topic that has fewer.
 */
@Configuration
public class KafkaTopicsConfig {

    @Value("${payflow.kafka.partitions:3}")
    private int partitions;

    @Value("${payflow.kafka.replication-factor:1}")
    private int replicationFactor;

    /** Replies to saga commands, keyed by payment id (per-payment order). */
    @Bean
    public NewTopic ledgerRepliesTopic() {
        return TopicBuilder.name(LedgerTopics.REPLIES).partitions(partitions).replicas(replicationFactor).build();
    }

    /** payment-service's dead letters for replies; same partition count as the source topic. */
    @Bean
    public NewTopic ledgerRepliesDeadLetterTopic() {
        return TopicBuilder.name(LedgerTopics.REPLIES_DLT).partitions(partitions).replicas(replicationFactor).build();
    }

    /** Replies to payout commands, keyed by payout id (per-payout order). */
    @Bean
    public NewTopic payoutLedgerRepliesTopic() {
        return TopicBuilder.name(LedgerTopics.PAYOUT_REPLIES).partitions(partitions).replicas(replicationFactor).build();
    }

    /** payout-service's dead letters for payout replies. */
    @Bean
    public NewTopic payoutLedgerRepliesDeadLetterTopic() {
        return TopicBuilder.name(LedgerTopics.PAYOUT_REPLIES_DLT).partitions(partitions).replicas(replicationFactor).build();
    }
}
