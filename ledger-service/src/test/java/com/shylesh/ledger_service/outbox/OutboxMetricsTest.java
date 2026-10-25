package com.shylesh.ledger_service.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxMetricsTest {

    private OutboxEventRepository repository;
    private MeterRegistry registry;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(repository, registry);
    }

    @Test
    void aScrapeReadsWhatTheLastRefreshFoundAndNeverTheDatabase() {
        when(repository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(7L);
        when(repository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(1L);
        when(repository.findOldestCreatedAt(OutboxEventStatus.PENDING)).thenReturn(LocalDateTime.now().minusSeconds(150));
        metrics.refresh();
        clearInvocations(repository);

        assertThat(registry.get("outbox.pending").gauge().value()).isEqualTo(7);
        assertThat(registry.get("outbox.failed").gauge().value()).isEqualTo(1);
        assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isBetween(150.0, 160.0);
        verifyNoInteractions(repository);
    }

    @Test
    void withTheDatabaseGoneTheValuesAreUnknownUntilItAnswersAgain() {
        when(repository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(3L);
        when(repository.findOldestCreatedAt(OutboxEventStatus.PENDING)).thenReturn(LocalDateTime.now().minusSeconds(5));
        metrics.refresh();

        // a message in flight when the database went away must not age into an OutboxBacklog alert
        when(repository.countByStatus(any())).thenThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"));
        metrics.refresh();   // logs, does not throw

        assertThat(registry.get("outbox.pending").gauge().value()).isNaN();
        assertThat(registry.get("outbox.failed").gauge().value()).isNaN();
        assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isNaN();

        reset(repository);
        when(repository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(0L);
        metrics.refresh();
        assertThat(registry.get("outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isZero();
    }

    @Test
    void beforeTheFirstRefreshNothingIsKnown() {
        assertThat(registry.get("outbox.pending").gauge().value()).isNaN();
        assertThat(registry.get("outbox.failed").gauge().value()).isNaN();
        assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isNaN();
    }
}
