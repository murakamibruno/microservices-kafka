package br.com.microservices.orchestrated.productvalidationservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProductValidationProducer {

    private final KafkaProducer<String, String> kafkaProducer;

    @Value("${spring.kafka.topic.orchestrator}")
    private String orchestratorTopic;

    public void sendEvent(String payload) {
        try {
            log.info("Sending event to topic {} with data {}", orchestratorTopic, payload);
            kafkaProducer.send(new ProducerRecord<>(orchestratorTopic, payload));
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", orchestratorTopic, payload, ex);
        }
    }

}
