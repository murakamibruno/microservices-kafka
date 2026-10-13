package br.com.microservices.orchestrated.orchestratorservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RequiredArgsConstructor
@Component
public class SagaOrchestratorProducer {

    private static final String SOURCE = "ORCHESTRATOR";

    private final KafkaProducer<String, String> kafkaProducer;

    @Value("${spring.kafka.producer.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    /**
     * Envio síncrono: qualquer falha é propagada para que o error handler do consumer não commite o offset
     * e a mensagem de entrada seja reprocessada (ADR-03).
     */
    public void sendEvent(String payload, String topic, String transactionId, String inputMessageId) {
        log.info("Sending event to topic {} with data {}", topic, payload);
        try {
            var record = new ProducerRecord<>(topic, transactionId, payload);
            record.headers().add("x-message-id", messageId(inputMessageId, topic).getBytes(UTF_8));
            record.headers().add("x-transaction-id", transactionId.getBytes(UTF_8));
            record.headers().add("x-source", SOURCE.getBytes(UTF_8));
            kafkaProducer.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending data to topic " + topic, ex);
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", topic, payload, ex);
            throw new IllegalStateException("Error sending data to topic " + topic, ex);
        }
    }

    /** Determinístico: reprocessar a mesma mensagem de entrada gera o mesmo id e o Inbox downstream deduplica. */
    static String messageId(String inputMessageId, String targetTopic) {
        return UUID.nameUUIDFromBytes((inputMessageId + ":" + targetTopic).getBytes(UTF_8)).toString();
    }

}
