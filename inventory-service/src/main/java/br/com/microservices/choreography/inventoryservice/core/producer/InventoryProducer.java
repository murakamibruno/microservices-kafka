package br.com.microservices.choreography.inventoryservice.core.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class InventoryProducer {

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
