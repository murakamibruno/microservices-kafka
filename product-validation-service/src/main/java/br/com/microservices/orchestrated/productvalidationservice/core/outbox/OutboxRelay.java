package br.com.microservices.orchestrated.productvalidationservice.core.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxMessageRepository repository;
    private final KafkaProducer<String, String> kafkaProducer;
    private final OutboxProperties props;
    private final OutboxMetrics metrics;

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        var batch = repository.lockNextBatch(LocalDateTime.now(), props.getRelay().getBatchSize());
        for (var message : batch) {
            // Interrompe o lote no primeiro erro: com o broker fora do ar, evita N timeouts seguidos.
            if (!publish(message)) {
                break;
            }
        }
    }

    private boolean publish(OutboxMessage message) {
        try {
            var record = new ProducerRecord<>(message.getTopic(), message.getAggregateId(), message.getPayload());
            record.headers().add("x-message-id", message.getId().toString().getBytes(UTF_8));
            record.headers().add("x-transaction-id", message.getAggregateId().getBytes(UTF_8));
            record.headers().add("x-source", props.getSource().getBytes(UTF_8));
            kafkaProducer.send(record).get(props.getRelay().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            message.setStatus(EOutboxStatus.SENT);
            message.setSentAt(LocalDateTime.now());
            log.info("Outbox message {} published to {} (transactionId={}, attempts={})",
                message.getId(), message.getTopic(), message.getAggregateId(), message.getAttempts());
            metrics.published();
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            registerFailure(message, ex);
            return false;
        } catch (Exception ex) {
            registerFailure(message, ex);
            return false;
        }
    }

    private void registerFailure(OutboxMessage message, Exception ex) {
        metrics.publishFailed();
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(truncate(String.valueOf(ex.getMessage())));
        message.setNextAttemptAt(LocalDateTime.now().plus(backoff(message.getAttempts())));
        if (message.getAttempts() >= props.getRelay().getMaxAttempts()) {
            message.setStatus(EOutboxStatus.FAILED);
        }
        log.error("Error publishing outbox message {} to {} (transactionId={}, attempts={}, status={})",
            message.getId(), message.getTopic(), message.getAggregateId(), message.getAttempts(),
            message.getStatus(), ex);
    }

    Duration backoff(int attempts) {
        var relay = props.getRelay();
        var exponent = Math.min(Math.max(attempts - 1, 0), 30);
        var delay = Math.min(relay.getBaseBackoffMs() * (1L << exponent), relay.getMaxBackoffMs());
        return Duration.ofMillis(delay);
    }

    private String truncate(String value) {
        return value.length() > MAX_ERROR_LENGTH ? value.substring(0, MAX_ERROR_LENGTH) : value;
    }

}
