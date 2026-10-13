package br.com.microservices.orchestrated.orderservice.core.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private OutboxMessageRepository repository;
    private KafkaProducer<String, String> producer;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        producer = mock(KafkaProducer.class);
        var props = new OutboxProperties();
        props.setSource("ORDER_SERVICE");
        props.getRelay().setMaxAttempts(3);
        relay = new OutboxRelay(repository, producer, props);
    }

    private OutboxMessage message(int attempts) {
        var now = LocalDateTime.now();
        return OutboxMessage.builder()
            .id(UUID.randomUUID().toString()).aggregateType("ORDER").aggregateId("tx-1")
            .eventType("ORDER_CREATED").topic("start-saga").payload("{}")
            .status(EOutboxStatus.PROCESSING).attempts(attempts)
            .lockedUntil(now.plusSeconds(30)).lockedBy("me")
            .nextAttemptAt(now).createdAt(now).build();
    }

    private void whenClaims(OutboxMessage first, OutboxMessage... rest) {
        var stub = when(repository.claimNext(any(), any(), any())).thenReturn(Optional.of(first));
        for (var message : rest) {
            stub = stub.thenReturn(Optional.of(message));
        }
        stub.thenReturn(Optional.empty());
    }

    @Test
    void publishesWithKeyAndHeadersAndMarksSent() {
        var message = message(0);
        whenClaims(message);
        when(producer.send(any())).thenReturn(CompletableFuture.completedFuture((RecordMetadata) null));

        relay.publishPending();

        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture());
        var record = captor.getValue();
        assertThat(record.topic()).isEqualTo("start-saga");
        assertThat(record.key()).isEqualTo("tx-1");
        assertThat(new String(record.headers().lastHeader("x-message-id").value())).isEqualTo(message.getId());
        assertThat(new String(record.headers().lastHeader("x-transaction-id").value())).isEqualTo("tx-1");
        assertThat(new String(record.headers().lastHeader("x-source").value())).isEqualTo("ORDER_SERVICE");
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
        assertThat(message.getLockedUntil()).isNull();
        verify(repository).save(message);
    }

    @Test
    void failureReturnsToPendingAndSchedulesRetry() {
        var message = message(0);
        var before = LocalDateTime.now();
        whenClaims(message);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("broker down");
        assertThat(message.getNextAttemptAt()).isAfter(before);
        assertThat(message.getLockedUntil()).isNull();
        verify(repository).save(message);
    }

    @Test
    void becomesFailedAfterMaxAttempts() {
        var message = message(2);
        whenClaims(message);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(message.getAttempts()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.FAILED);
    }

    @Test
    void stopsBatchOnFirstError() {
        var first = message(0);
        var second = message(0);
        whenClaims(first, second);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        verify(producer, times(1)).send(any());
        verify(repository, times(1)).claimNext(any(), any(), any());
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
