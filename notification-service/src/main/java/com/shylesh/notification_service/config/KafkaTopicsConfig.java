package com.shylesh.notification_service.config;

import com.shylesh.notification_service.dlt.NotificationTopics;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics owned by notification-service, created with explicit partition counts instead of
 * relying on broker auto-creation.
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

    /** Notifications that failed for good, keyed by payment id. */
    @Bean
    public NewTopic notificationDeadLetterTopic() {
        return TopicBuilder.name(NotificationTopics.NOTIFICATION_DEAD_LETTER).partitions(partitions).replicas(replicationFactor).build();
    }

}
