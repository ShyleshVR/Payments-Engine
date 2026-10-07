package com.shylesh.notification_service.config;

import com.shylesh.notification_service.event.InvalidEventException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerFailureClassifierTest {

    @Test
    void databaseDownAsSeenThroughTheListenerIsTransient() {
        // What a DB outage looks like by the time it reaches the error handler.
        Exception failure = new ListenerExecutionFailedException("Listener method threw exception",
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 30000ms")));

        assertThat(ConsumerFailureClassifier.isTransientInfrastructureFailure(failure)).isTrue();
    }

    @Test
    void queryTimeoutIsTransient() {
        assertThat(ConsumerFailureClassifier.isTransientInfrastructureFailure(new QueryTimeoutException("timeout"))).isTrue();
    }

    @Test
    void messageProblemsAreNotTransient() {
        assertThat(ConsumerFailureClassifier.isTransientInfrastructureFailure(new InvalidEventException("bad"))).isFalse();
        assertThat(ConsumerFailureClassifier.isTransientInfrastructureFailure(new DataIntegrityViolationException("constraint"))).isFalse();
        assertThat(ConsumerFailureClassifier.isTransientInfrastructureFailure(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void infrastructureBackOffNeverGivesUpAndCapsAtSixtySeconds() {
        var execution = KafkaConsumerConfig.unboundedInfrastructureBackOff().start();
        long last = 0;
        for (int i = 0; i < 1000; i++) {
            last = execution.nextBackOff();
            assertThat(last).isPositive();
        }
        assertThat(last).isEqualTo(60_000L);
    }

    @Test
    void otherFailuresGetAFewRetriesThenStop() {
        var execution = KafkaConsumerConfig.boundedBackOff().start();
        int retries = 0;
        while (execution.nextBackOff() != org.springframework.util.backoff.BackOffExecution.STOP) {
            retries++;
        }
        assertThat(retries).isEqualTo(4);
    }
}
