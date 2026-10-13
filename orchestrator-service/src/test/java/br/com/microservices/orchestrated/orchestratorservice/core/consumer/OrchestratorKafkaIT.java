package br.com.microservices.orchestrated.orchestratorservice.core.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.error-handler.max-retries=2",
    "spring.kafka.error-handler.initial-interval-ms=100",
    "spring.kafka.error-handler.max-interval-ms=200"
})
class OrchestratorKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static String startSagaEvent(String transactionId) {
        return "{\"id\":\"evt-" + transactionId + "\",\"transactionId\":\"" + transactionId + "\","
            + "\"orderId\":\"o-1\",\"payload\":{\"id\":\"o-1\",\"transactionId\":\"" + transactionId + "\"}}";
    }

    private void publish(String topic, String key, String value, String messageId) {
        var props = Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(props)) {
            var record = new ProducerRecord<>(topic, key, value);
            if (messageId != null) {
                record.headers().add("x-message-id", messageId.getBytes());
            }
            producer.send(record).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void startSagaIsForwardedWithKeyAndDeterministicMessageId() {
        publish("start-saga", "tx-a", startSagaEvent("tx-a"), "in-a");
        publish("start-saga", "tx-a", startSagaEvent("tx-a"), "in-a"); // reentrega do mesmo input

        var records = consume("product-validation-success", "tx-a", 2);

        assertThat(records).allSatisfy(r -> {
            assertThat(r.key()).isEqualTo("tx-a");
            assertThat(new String(r.headers().lastHeader("x-source").value())).isEqualTo("ORCHESTRATOR");
        });
        assertThat(header(records.get(0), "x-message-id")).isEqualTo(header(records.get(1), "x-message-id"));
    }

    @Test
    void poisonMessageGoesToTheDltWithoutBlockingThePartition() {
        publish("orchestrator", "tx-poison", "not-a-json", "in-poison");
        publish("start-saga", "tx-b", startSagaEvent("tx-b"), "in-b");

        var dead = consume("orchestrator.DLT", "tx-poison", 1);
        var next = consume("product-validation-success", "tx-b", 1);

        assertThat(dead.get(0).value()).isEqualTo("not-a-json");
        assertThat(next).hasSize(1);
    }

    @Test
    void unknownSourceAndStatusGoesToTheDlt() {
        // sem source/status válidos para a tabela do SagaHandler: ValidationException, não-retentável
        publish("orchestrator", "tx-c", startSagaEvent("tx-c"), "in-c");

        assertThat(consume("orchestrator.DLT", "tx-c", 1)).hasSize(1);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value());
    }

    private List<ConsumerRecord<String, String>> consume(String topic, String key, int expected) {
        var props = Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var found = new java.util.ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline && found.size() < expected) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        assertThat(found).as("records with key %s on %s", key, topic).hasSize(expected);
        return found;
    }

}
