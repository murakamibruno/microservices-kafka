package br.com.microservices.orchestrated.inventoryservice.core.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private OutboxMessageRepository repository;
    private KafkaProducer<String, String> producer;
    private OutboxProperties props;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        producer = mock(KafkaProducer.class);
        props = new OutboxProperties();
        props.setSource("INVENTORY_SERVICE");
        props.getRelay().setMaxAttempts(3);
        relay = new OutboxRelay(repository, producer, props, new OutboxMetrics(new SimpleMeterRegistry(), repository));
    }

    private OutboxMessage message(int attempts) {
        var now = LocalDateTime.now();
        return OutboxMessage.builder()
            .id(UUID.randomUUID()).aggregateType("INVENTORY").aggregateId("tx-1")
            .eventType("INVENTORY_SUCCESS").topic("orchestrator").payload("{}")
            .status(EOutboxStatus.PENDING).attempts(attempts)
            .nextAttemptAt(now).createdAt(now).build();
    }

    private void whenBatch(OutboxMessage... messages) {
        when(repository.lockNextBatch(any(), anyInt())).thenReturn(List.of(messages));
    }

    @Test
    void publishesWithKeyAndHeadersAndMarksSent() {
        var message = message(0);
        whenBatch(message);
        when(producer.send(any())).thenReturn(CompletableFuture.completedFuture((RecordMetadata) null));

        relay.publishPending();

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture());
        var record = captor.getValue();
        assertThat(record.topic()).isEqualTo("orchestrator");
        assertThat(record.key()).isEqualTo("tx-1");
        assertThat(new String(record.headers().lastHeader("x-message-id").value()))
            .isEqualTo(message.getId().toString());
        assertThat(new String(record.headers().lastHeader("x-transaction-id").value())).isEqualTo("tx-1");
        assertThat(new String(record.headers().lastHeader("x-source").value())).isEqualTo("INVENTORY_SERVICE");
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
    }

    @Test
    void failureKeepsPendingAndSchedulesRetry() {
        var message = message(0);
        var before = LocalDateTime.now();
        whenBatch(message);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("broker down");
        assertThat(message.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void becomesFailedAfterMaxAttempts() {
        var message = message(2);
        whenBatch(message);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(message.getAttempts()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.FAILED);
    }

    @Test
    void stopsBatchOnFirstError() {
        var first = message(0);
        var second = message(0);
        whenBatch(first, second);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        verify(producer, times(1)).send(any());
        assertThat(second.getAttempts()).isZero();
    }

    @Test
    void backoffIsExponentialAndCapped() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(relay.backoff(20)).isEqualTo(Duration.ofSeconds(60));
        assertThat(relay.backoff(500)).isEqualTo(Duration.ofSeconds(60));
    }

}
