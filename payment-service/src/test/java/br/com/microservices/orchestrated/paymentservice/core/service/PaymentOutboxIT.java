package br.com.microservices.orchestrated.paymentservice.core.service;

import br.com.microservices.orchestrated.paymentservice.core.dto.Event;
import br.com.microservices.orchestrated.paymentservice.core.dto.Order;
import br.com.microservices.orchestrated.paymentservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.paymentservice.core.dto.Product;
import br.com.microservices.orchestrated.paymentservice.core.enums.EPaymentStatus;
import br.com.microservices.orchestrated.paymentservice.core.enums.ESagaStatus;
import br.com.microservices.orchestrated.paymentservice.core.inbox.InboxMessageRepository;
import br.com.microservices.orchestrated.paymentservice.core.outbox.EOutboxStatus;
import br.com.microservices.orchestrated.paymentservice.core.outbox.OutboxMessage;
import br.com.microservices.orchestrated.paymentservice.core.outbox.OutboxMessageRepository;
import br.com.microservices.orchestrated.paymentservice.core.outbox.OutboxRelay;
import br.com.microservices.orchestrated.paymentservice.core.repository.PaymentRepository;
import br.com.microservices.orchestrated.paymentservice.core.utils.JsonUtil;
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
class PaymentOutboxIT {

    private static final String SUCCESS_TOPIC = "payment-success";
    private static final String FAIL_TOPIC = "payment-fail";
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

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;
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
        paymentRepository.deleteAll();
    }

    private Event event(String transactionId, double unitValue) {
        var product = new Product();
        product.setCode("COMIC_BOOKS");
        product.setUnitValue(unitValue);
        var orderProduct = new OrderProducts();
        orderProduct.setProduct(product);
        orderProduct.setQuantity(2);
        var order = Order.builder()
            .id("order-" + transactionId).transactionId(transactionId)
            .products(List.of(orderProduct))
            .build();
        return Event.builder().id(UUID.randomUUID().toString()).transactionId(transactionId)
            .orderId(order.getId()).payload(order).source("PRODUCT_VALIDATION_SERVICE")
            .status(ESagaStatus.SUCCESS).build();
    }

    @Test
    void commitLeavesOneMessagePendingForTheOrchestrator() {
        paymentService.realizePayment(event("tx-1", 10.0), SUCCESS_TOPIC, "m-1");

        var messages = outboxRepository.findAll();
        assertThat(messages).hasSize(1);
        var message = messages.get(0);
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.PENDING);
        assertThat(message.getTopic()).isEqualTo(ORCHESTRATOR_TOPIC);
        assertThat(message.getAggregateId()).isEqualTo("tx-1");
        assertThat(message.getEventType()).isEqualTo("PAYMENT_SUCCESS");
        assertThat(paymentRepository.findAll()).singleElement()
            .satisfies(p -> assertThat(p.getStatus()).isEqualTo(EPaymentStatus.SUCCESS));
    }

    // CA-02
    @Test
    void failureBeforeCommitLeavesNoOutboxRowNorBusinessData() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            paymentService.realizePayment(event("tx-2", 10.0), SUCCESS_TOPIC, "m-2");
            throw new IllegalStateException("boom before commit");
        })).hasMessage("boom before commit");

        assertThat(outboxRepository.count()).isZero();
        assertThat(inboxRepository.count()).isZero();
        assertThat(paymentRepository.count()).isZero();
    }

    // CA-03
    @Test
    void redeliveredMessageHasASingleEffectAndNoRollbackPending() {
        paymentService.realizePayment(event("tx-3", 10.0), SUCCESS_TOPIC, "m-3");
        paymentService.realizePayment(event("tx-3", 10.0), SUCCESS_TOPIC, "m-3-redelivered");

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(inboxRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getEventType()).isEqualTo("PAYMENT_SUCCESS"));
    }

    @Test
    void sameTransactionOnDifferentTopicsIsNotADuplicate() {
        paymentService.realizePayment(event("tx-4", 10.0), SUCCESS_TOPIC, "m-4");
        paymentService.realizeRefund(event("tx-4", 10.0), FAIL_TOPIC, "m-4-fail");

        assertThat(outboxRepository.findAll()).extracting(OutboxMessage::getEventType)
            .containsExactlyInAnyOrder("PAYMENT_SUCCESS", "PAYMENT_FAIL");
        assertThat(paymentRepository.findAll()).singleElement()
            .satisfies(p -> assertThat(p.getStatus()).isEqualTo(EPaymentStatus.REFUND));
    }

    @Test
    void businessErrorCommitsRollbackPendingEvent() {
        paymentService.realizePayment(event("tx-5", 0.01), SUCCESS_TOPIC, "m-5");

        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getEventType()).isEqualTo("PAYMENT_ROLLBACK_PENDING"));
    }

    // CA-04
    @Test
    void concurrentRelaysNeverLockTheSameRows() throws Exception {
        for (int i = 0; i < 10; i++) {
            paymentService.realizePayment(event("tx-par-" + i, 10.0), SUCCESS_TOPIC, "m-par-" + i);
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
        paymentService.realizePayment(event("tx-6", 10.0), SUCCESS_TOPIC, "m-6");

        relay.publishPending();

        var record = consumeOne("tx-6");
        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getStatus()).isEqualTo(EOutboxStatus.SENT));
        assertThat(record.key()).isEqualTo("tx-6");
        assertThat(new String(record.headers().lastHeader("x-source").value())).isEqualTo("PAYMENT_SERVICE");
        var json = objectMapper.readTree(record.value());
        var fields = new TreeSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(
            "createdAt", "eventHistory", "id", "orderId", "payload", "source", "status", "transactionId");
        assertThat(json.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(json.get("source").asText()).isEqualTo("PAYMENT_SERVICE");
        assertThat(json.get("payload").get("totalAmount").asDouble()).isEqualTo(20.0);
        assertThat(json.get("payload").get("totalItems").asInt()).isEqualTo(2);
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
