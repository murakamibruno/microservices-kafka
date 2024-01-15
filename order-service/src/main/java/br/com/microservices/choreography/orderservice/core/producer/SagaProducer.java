package br.com.microservices.choreography.orderservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaProducer {

    private final KafkaProducer<String, String> kafkaProducer;
    @Value("${spring.kafka.topic.product-validation-start}")
    private String productValidationStartTopic;

    public void sendEvent(String payload) {
        try {
            log.info("Sending event to topic {} with data {}", productValidationStartTopic, payload);
            kafkaProducer.send(new ProducerRecord<>(productValidationStartTopic, payload));
        } catch (Exception ex) {
            log.error("Error trying to sending data to topic {} with data {}", productValidationStartTopic, payload, ex);
        }
    }

}
