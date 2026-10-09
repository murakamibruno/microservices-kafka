package br.com.microservices.orchestrated.orderservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaProducer {

    private final KafkaProducer<String, String> kafkaProducer;

    @Value("${spring.kafka.producer.send-timeout-ms:5000}")
    private long sendTimeoutMs;
    @Value("${spring.kafka.topic.start-saga}")
    private String startSagaTopic;

    public void sendEvent(String payload) {
        log.info("Sending event to topic {} with data {}", startSagaTopic, payload);
        try {
            kafkaProducer.send(new ProducerRecord<>(startSagaTopic, payload)).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending data to topic " + startSagaTopic, ex);
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", startSagaTopic, payload, ex);
            throw new IllegalStateException("Error sending data to topic " + startSagaTopic, ex);
        }
    }

}
