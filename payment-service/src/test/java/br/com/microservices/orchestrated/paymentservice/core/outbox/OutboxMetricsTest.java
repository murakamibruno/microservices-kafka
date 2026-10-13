package br.com.microservices.orchestrated.paymentservice.core.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxMetricsTest {

    private OutboxMessageRepository repository;
    private SimpleMeterRegistry registry;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(registry, repository);
    }

    @Test
    void gaugesReadTheOutboxState() {
        when(repository.countByStatus(EOutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(EOutboxStatus.FAILED)).thenReturn(2L);
        when(repository.findFirstByStatusOrderByCreatedAtAsc(EOutboxStatus.PENDING)).thenReturn(
            Optional.of(OutboxMessage.builder().createdAt(LocalDateTime.now().minusSeconds(120)).build()));

        assertThat(registry.get("outbox.pending.count").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("outbox.failed.count").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isBetween(120.0, 125.0);
    }

    @Test
    void oldestPendingAgeIsZeroWhenNothingIsPending() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(EOutboxStatus.PENDING)).thenReturn(Optional.empty());

        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isZero();
    }

    @Test
    void gaugeIsNaNWhenTheRepositoryFails() {
        when(repository.countByStatus(EOutboxStatus.PENDING)).thenThrow(new IllegalStateException("db down"));

        assertThat(registry.get("outbox.pending.count").gauge().value()).isNaN();
    }

    @Test
    void countersIncrement() {
        metrics.published();
        metrics.published();
        metrics.publishFailed();
        metrics.inboxDuplicate();

        assertThat(registry.get("outbox.published.total").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("outbox.publish.failures.total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("inbox.duplicates.total").counter().count()).isEqualTo(1.0);
    }

}
