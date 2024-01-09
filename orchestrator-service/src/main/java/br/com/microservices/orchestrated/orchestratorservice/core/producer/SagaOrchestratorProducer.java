package br.com.microservices.orchestrated.orchestratorservice.core.producer;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@AllArgsConstructor
@Component
public class SagaOrchestratorProducer {

    private final KafkaProducer<String, String> kafkaProducer;

    public void sendEvent(String payload, String topic) {
        try {
            log.info("Sending event to topic {} with data {}", topic, payload);
            kafkaProducer.send(new ProducerRecord<>(topic, payload));
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", topic, payload, ex);
        }
    }

}
