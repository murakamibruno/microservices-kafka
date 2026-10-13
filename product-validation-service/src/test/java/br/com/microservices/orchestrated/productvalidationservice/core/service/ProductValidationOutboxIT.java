package br.com.microservices.orchestrated.productvalidationservice.core.service;

import br.com.microservices.orchestrated.productvalidationservice.core.dto.Event;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.Order;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.productvalidationservice.core.enums.ESagaStatus;
import br.com.microservices.orchestrated.productvalidationservice.core.inbox.InboxMessageRepository;
import br.com.microservices.orchestrated.productvalidationservice.core.outbox.EOutboxStatus;
import br.com.microservices.orchestrated.productvalidationservice.core.outbox.OutboxMessage;
import br.com.microservices.orchestrated.productvalidationservice.core.outbox.OutboxMessageRepository;
import br.com.microservices.orchestrated.productvalidationservice.core.outbox.OutboxRelay;
import br.com.microservices.orchestrated.productvalidationservice.core.utils.JsonUtil;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.Product;
import br.com.microservices.orchestrated.productvalidationservice.core.repository.ValidationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
    // Relay disparado manualmente nos testes; o agendamento fica praticamente desligado.
    "outbox.relay.poll-interval-ms=3600000",
    "spring.kafka.consumer.auto-offset-reset=earliest"
})
class ProductValidationOutboxIT {

    private static final String SUCCESS_TOPIC = "product-validation-success";
    private static final String FAIL_TOPIC = "product-validation-fail";
    private static final String ORCHESTRATOR_TOPIC = "orchestrator";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired ProductValidationService productValidationService;
    @Autowired ValidationRepository validationRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired InboxMessageRepository inboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired TransactionTemplate tx;
    @Autowired JsonUtil jsonUtil;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAll();
        inboxRepository.deleteAll();
        validationRepository.deleteAll();
    }

    private Event event(String transactionId, String code) {
        var product = new Product();
        product.setCode(code);
        product.setUnitValue(10.0);
        var orderProduct = new OrderProducts();
        orderProduct.setProduct(product);
        orderProduct.setQuantity(1);
        var order = Order.builder()
            .id("order-" + transactionId).transactionId(transactionId)
            .products(List.of(orderProduct))
            .build();
        return Event.builder().id(UUID.randomUUID().toString()).transactionId(transactionId)
            .orderId(order.getId()).payload(order).source("ORCHESTRATOR")
            .status(ESagaStatus.SUCCESS).build();
    }

    @Test
    void commitLeavesOneMessagePendingForTheOrchestrator() {
        productValidationService.validateExistingProduct(event("tx-1", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-1");

        var messages = outboxRepository.findAll();
        assertThat(messages).hasSize(1);
        var message = messages.get(0);
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.PENDING);
        assertThat(message.getTopic()).isEqualTo(ORCHESTRATOR_TOPIC);
        assertThat(message.getAggregateId()).isEqualTo("tx-1");
        assertThat(message.getEventType()).isEqualTo("PRODUCT_VALIDATION_SUCCESS");
        assertThat(validationRepository.findAll()).singleElement()
            .satisfies(v -> assertThat(v.isSuccess()).isTrue());
    }

    // CA-02
    @Test
    void failureBeforeCommitLeavesNoOutboxRowNorBusinessData() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            productValidationService.validateExistingProduct(event("tx-2", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-2");
            throw new IllegalStateException("boom before commit");
        })).hasMessage("boom before commit");

        assertThat(outboxRepository.count()).isZero();
        assertThat(inboxRepository.count()).isZero();
        assertThat(validationRepository.count()).isZero();
    }

    // CA-03
    @Test
    void redeliveredMessageHasASingleEffectAndNoRollbackPending() {
        productValidationService.validateExistingProduct(event("tx-3", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-3");
        productValidationService.validateExistingProduct(event("tx-3", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-3-redelivered");

        assertThat(validationRepository.count()).isEqualTo(1);
        assertThat(inboxRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getEventType()).isEqualTo("PRODUCT_VALIDATION_SUCCESS"));
    }

    @Test
    void sameTransactionOnDifferentTopicsIsNotADuplicate() {
        productValidationService.validateExistingProduct(event("tx-4", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-4");
        productValidationService.rollbackEvent(event("tx-4", "COMIC_BOOKS"), FAIL_TOPIC, "m-4-fail");

        assertThat(outboxRepository.findAll()).extracting(OutboxMessage::getEventType)
            .containsExactlyInAnyOrder("PRODUCT_VALIDATION_SUCCESS", "PRODUCT_VALIDATION_FAIL");
        assertThat(validationRepository.findAll()).singleElement()
            .satisfies(v -> assertThat(v.isSuccess()).isFalse());
    }

    @Test
    void businessErrorCommitsRollbackPendingEvent() {
        productValidationService.validateExistingProduct(event("tx-5", "UNKNOWN_PRODUCT"), SUCCESS_TOPIC, "m-5");

        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getEventType()).isEqualTo("PRODUCT_VALIDATION_ROLLBACK_PENDING"));
        assertThat(validationRepository.count()).isZero();
        assertThat(inboxRepository.count()).isEqualTo(1);
    }

    @Test
    void redeliveredBusinessErrorDoesNotEnqueueTwice() {
        productValidationService.validateExistingProduct(event("tx-7", "UNKNOWN_PRODUCT"), SUCCESS_TOPIC, "m-7");
        productValidationService.validateExistingProduct(event("tx-7", "UNKNOWN_PRODUCT"), SUCCESS_TOPIC, "m-7-redelivered");

        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(inboxRepository.count()).isEqualTo(1);
    }


    // CA-04
    @Test
    void concurrentRelaysNeverLockTheSameRows() throws Exception {
        for (int i = 0; i < 10; i++) {
            productValidationService.validateExistingProduct(event("tx-par-" + i, "COMIC_BOOKS"), SUCCESS_TOPIC, "m-par-" + i);
        }
        var firstLocked = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> tx.execute(status -> {
                var batch = outboxRepository.lockNextBatch(java.time.LocalDateTime.now(), 6);
                firstLocked.countDown();
                await(secondDone);
                return ids(batch);
            }));
            var second = executor.submit(() -> {
                await(firstLocked);
                var ids = tx.execute(status ->
                    ids(outboxRepository.lockNextBatch(java.time.LocalDateTime.now(), 10)));
                secondDone.countDown();
                return ids;
            });
            var secondIds = second.get(30, TimeUnit.SECONDS);
            var firstIds = first.get(30, TimeUnit.SECONDS);

            assertThat(firstIds).hasSize(6);
            assertThat(secondIds).hasSize(4);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        } finally {
            executor.shutdownNow();
        }
    }

    // CA-05
    @Test
    void publishedPayloadKeepsTheCurrentContract() throws Exception {
        productValidationService.validateExistingProduct(event("tx-6", "COMIC_BOOKS"), SUCCESS_TOPIC, "m-6");

        relay.publishPending();

        var record = consumeOne("tx-6");
        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getStatus()).isEqualTo(EOutboxStatus.SENT));
        assertThat(record.key()).isEqualTo("tx-6");
        assertThat(new String(record.headers().lastHeader("x-source").value())).isEqualTo("PRODUCT_VALIDATION_SERVICE");
        var json = objectMapper.readTree(record.value());
        var fields = new TreeSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(
            "createdAt", "eventHistory", "id", "orderId", "payload", "source", "status", "transactionId");
        assertThat(json.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(json.get("source").asText()).isEqualTo("PRODUCT_VALIDATION_SERVICE");
        assertThat(jsonUtil.toEvent(record.value()).getEventHistory()).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<UUID> ids(List<OutboxMessage> messages) {
        var ids = new HashSet<UUID>();
        messages.forEach(m -> ids.add(m.getId()));
        return ids;
    }

    private ConsumerRecord<String, String> consumeOne(String key) {
        var props = Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(ORCHESTRATOR_TOPIC));
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + ORCHESTRATOR_TOPIC);
    }

}
