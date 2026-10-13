package br.com.microservices.orchestrated.orderservice.core.outbox;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxMessageRepository repository;
    private final KafkaProducer<String, String> kafkaProducer;
    private final OutboxProperties props;
    private final String instanceId = UUID.randomUUID().toString();

    public OutboxRelay(OutboxMessageRepository repository,
                       KafkaProducer<String, String> kafkaProducer,
                       OutboxProperties props) {
        this.repository = repository;
        this.kafkaProducer = kafkaProducer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    public void publishPending() {
        var relay = props.getRelay();
        for (int i = 0; i < relay.getBatchSize(); i++) {
            var now = LocalDateTime.now();
            var claimed = repository.claimNext(now, now.plus(Duration.ofMillis(relay.getLeaseMs())), instanceId);
            if (claimed.isEmpty()) {
                return;
            }
            // Interrompe o lote no primeiro erro: com o broker fora do ar, evita N timeouts seguidos.
            if (!publish(claimed.get())) {
                return;
            }
        }
    }

    private boolean publish(OutboxMessage message) {
        try {
            var record = new ProducerRecord<>(message.getTopic(), message.getAggregateId(), message.getPayload());
            record.headers().add("x-message-id", message.getId().getBytes(UTF_8));
            record.headers().add("x-transaction-id", message.getAggregateId().getBytes(UTF_8));
            record.headers().add("x-source", props.getSource().getBytes(UTF_8));
            kafkaProducer.send(record).get(props.getRelay().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            message.setStatus(EOutboxStatus.SENT);
            message.setSentAt(LocalDateTime.now());
            release(message);
            repository.save(message);
            log.info("Outbox message {} published to {} (transactionId={}, attempts={})",
                message.getId(), message.getTopic(), message.getAggregateId(), message.getAttempts());
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
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(truncate(String.valueOf(ex.getMessage())));
        message.setNextAttemptAt(LocalDateTime.now().plus(backoff(message.getAttempts())));
        message.setStatus(message.getAttempts() >= props.getRelay().getMaxAttempts()
            ? EOutboxStatus.FAILED : EOutboxStatus.PENDING);
        release(message);
        repository.save(message);
        log.error("Error publishing outbox message {} to {} (transactionId={}, attempts={}, status={})",
            message.getId(), message.getTopic(), message.getAggregateId(), message.getAttempts(),
            message.getStatus(), ex);
    }

    private void release(OutboxMessage message) {
        message.setLockedUntil(null);
        message.setLockedBy(null);
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
