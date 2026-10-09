package br.com.microservices.orchestrated.orchestratorservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class SagaOrchestratorProducer {

    private final KafkaProducer<String, String> kafkaProducer;

    @Value("${spring.kafka.producer.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    public void sendEvent(String payload, String topic) {
        log.info("Sending event to topic {} with data {}", topic, payload);
        try {
            kafkaProducer.send(new ProducerRecord<>(topic, payload)).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending data to topic " + topic, ex);
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", topic, payload, ex);
            throw new IllegalStateException("Error sending data to topic " + topic, ex);
        }
    }

}
