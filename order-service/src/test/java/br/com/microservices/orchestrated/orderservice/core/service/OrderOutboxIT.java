package br.com.microservices.orchestrated.orderservice.core.service;

import br.com.microservices.orchestrated.orderservice.core.consumer.EventConsumer;
import br.com.microservices.orchestrated.orderservice.core.document.Event;
import br.com.microservices.orchestrated.orderservice.core.document.Order;
import br.com.microservices.orchestrated.orderservice.core.document.OrderProducts;
import br.com.microservices.orchestrated.orderservice.core.document.Product;
import br.com.microservices.orchestrated.orderservice.core.dto.OrderRequest;
import br.com.microservices.orchestrated.orderservice.core.inbox.InboxMessageRepository;
import br.com.microservices.orchestrated.orderservice.core.outbox.EOutboxStatus;
import br.com.microservices.orchestrated.orderservice.core.outbox.OutboxMessage;
import br.com.microservices.orchestrated.orderservice.core.outbox.OutboxCleanupJob;
import br.com.microservices.orchestrated.orderservice.core.outbox.OutboxMessageRepository;
import br.com.microservices.orchestrated.orderservice.core.outbox.OutboxRelay;
import br.com.microservices.orchestrated.orderservice.core.repository.EventRepository;
import br.com.microservices.orchestrated.orderservice.core.repository.OrderRepository;
import br.com.microservices.orchestrated.orderservice.core.utils.JsonUtil;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Callable;
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
class OrderOutboxIT {

    private static final String START_SAGA_TOPIC = "start-saga";
    private static final String NOTIFY_ENDING_TOPIC = "notify-ending";

    // MongoDBContainer já sobe como replica set de um nó (necessário para transações).
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired OrderService orderService;
    @Autowired EventService eventService;
    @Autowired OrderRepository orderRepository;
    @Autowired EventRepository eventRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired InboxMessageRepository inboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired OutboxCleanupJob cleanupJob;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Autowired br.com.microservices.orchestrated.orderservice.core.inbox.InboxService inboxService;
    @Autowired EventConsumer consumer;
    @Autowired TransactionTemplate tx;
    @Autowired JsonUtil jsonUtil;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAll();
        inboxRepository.deleteAll();
        eventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private OrderRequest request() {
        var product = new Product("COMIC_BOOKS", 10.0);
        return new OrderRequest(List.of(new OrderProducts(product, 2)));
    }

    private Event endingEvent(String transactionId) {
        var order = Order.builder().id("order-" + transactionId).transactionId(transactionId).build();
        return Event.builder().id(UUID.randomUUID().toString()).transactionId(transactionId)
            .orderId(order.getId()).payload(order).source("ORCHESTRATOR").status("SUCCESS").build();
    }

    @Test
    void commitStoresOrderEventAndPendingOutboxMessage() {
        var order = orderService.createOrder(request());

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(m -> {
            assertThat(m.getStatus()).isEqualTo(EOutboxStatus.PENDING);
            assertThat(m.getTopic()).isEqualTo(START_SAGA_TOPIC);
            assertThat(m.getAggregateId()).isEqualTo(order.getTransactionId());
            assertThat(m.getEventType()).isEqualTo("ORDER_CREATED");
        });
    }

    // CA-02
    @Test
    void failureBeforeCommitLeavesNothingBehind() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            orderService.createOrder(request());
            throw new IllegalStateException("boom before commit");
        })).hasMessage("boom before commit");

        assertThat(orderRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    // CA-03
    @Test
    void redeliveredNotifyEndingHasASingleEffect() {
        var event = endingEvent("tx-1");
        eventService.notifyEnding(event, NOTIFY_ENDING_TOPIC, "m-1");
        eventService.notifyEnding(endingEvent("tx-1"), NOTIFY_ENDING_TOPIC, "m-1-redelivered");

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(inboxRepository.count()).isEqualTo(1);
    }

    @Test
    void consumerDeliversThroughTheInbox() {
        var payload = jsonUtil.toJson(endingEvent("tx-2"));
        consumer.consumeNotifyEndingEvent(payload, NOTIFY_ENDING_TOPIC, "m-2");
        consumer.consumeNotifyEndingEvent(payload, NOTIFY_ENDING_TOPIC, "m-2");

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    // CA-04
    @Test
    void concurrentClaimsNeverReturnTheSameMessage() throws Exception {
        for (int i = 0; i < 10; i++) {
            orderService.createOrder(request());
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<List<String>>>();
            for (int worker = 0; worker < 4; worker++) {
                var instance = "instance-" + worker;
                tasks.add(() -> {
                    var claimed = new ArrayList<String>();
                    Optional<OutboxMessage> message;
                    do {
                        var now = LocalDateTime.now();
                        message = outboxRepository.claimNext(now, now.plusSeconds(30), instance);
                        message.ifPresent(m -> claimed.add(m.getId()));
                    } while (message.isPresent());
                    return claimed;
                });
            }
            var all = new ArrayList<String>();
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                all.addAll(future.get());
            }
            assertThat(all).hasSize(10).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseMakesAProcessingMessageClaimableAgain() {
        orderService.createOrder(request());
        var now = LocalDateTime.now();
        var claimed = outboxRepository.claimNext(now, now.minusSeconds(1), "dead-instance").orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(EOutboxStatus.PROCESSING);

        assertThat(outboxRepository.claimNext(now.minusSeconds(10), now.plusSeconds(30), "other")).isEmpty();
        var reclaimed = outboxRepository.claimNext(LocalDateTime.now(), LocalDateTime.now().plusSeconds(30), "alive");
        assertThat(reclaimed).hasValueSatisfying(m -> {
            assertThat(m.getId()).isEqualTo(claimed.getId());
            assertThat(m.getLockedBy()).isEqualTo("alive");
        });
    }

    // CA-05
    @Test
    void publishedPayloadKeepsTheCurrentContract() throws Exception {
        var order = orderService.createOrder(request());

        relay.publishPending();

        var record = consumeOne(order.getTransactionId());
        assertThat(outboxRepository.findAll()).singleElement()
            .satisfies(m -> assertThat(m.getStatus()).isEqualTo(EOutboxStatus.SENT));
        assertThat(new String(record.headers().lastHeader("x-source").value())).isEqualTo("ORDER_SERVICE");
        var sent = jsonUtil.toEvent(record.value());
        assertThat(sent.getTransactionId()).isEqualTo(order.getTransactionId());
        assertThat(sent.getOrderId()).isEqualTo(order.getId());
        assertThat(sent.getPayload().getProducts()).hasSize(1);
    }

    private ConsumerRecord<String, String> consumeOne(String key) {
        var props = Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var kafkaConsumer = new KafkaConsumer<String, String>(props)) {
            kafkaConsumer.subscribe(List.of(START_SAGA_TOPIC));
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : kafkaConsumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + START_SAGA_TOPIC);
    }

    @Test
    void cleanupRemovesOnlySentMessagesOlderThanTheRetention() {
        var now = LocalDateTime.now();
        outboxRepository.save(outboxSample(EOutboxStatus.SENT, now.minusHours(100), now.minusHours(100)));
        var recentSent = outboxRepository.save(outboxSample(EOutboxStatus.SENT, now, now));
        outboxRepository.save(outboxSample(EOutboxStatus.PENDING, now.minusHours(100), null));

        cleanupJob.purgeSent();

        assertThat(outboxRepository.findAll()).extracting(OutboxMessage::getId)
            .contains(recentSent.getId()).hasSize(2);
        assertThat(outboxRepository.findAll()).extracting(OutboxMessage::getStatus)
            .containsExactlyInAnyOrder(EOutboxStatus.SENT, EOutboxStatus.PENDING);
    }

    @Test
    void metricsReflectTheOutboxState() {
        var now = LocalDateTime.now();
        outboxRepository.save(outboxSample(EOutboxStatus.PENDING, now.minusSeconds(90), null));
        outboxRepository.save(outboxSample(EOutboxStatus.FAILED, now, null));
        outboxRepository.save(outboxSample(EOutboxStatus.SENT, now, now));

        assertThat(meterRegistry.get("outbox.pending.count").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.failed.count").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.oldest.pending.age.seconds").gauge().value()).isGreaterThanOrEqualTo(90.0);
    }

    @Test
    void duplicateInboxRegistrationIncrementsTheMetric() {
        var before = meterRegistry.get("inbox.duplicates.total").counter().count();

        tx.executeWithoutResult(status -> {
            assertThat(inboxService.register("metrics-topic", "tx-metrics", "m-1")).isTrue();
            assertThat(inboxService.register("metrics-topic", "tx-metrics", "m-2")).isFalse();
        });

        assertThat(meterRegistry.get("inbox.duplicates.total").counter().count()).isEqualTo(before + 1);
    }

    private OutboxMessage outboxSample(EOutboxStatus status, LocalDateTime createdAt, LocalDateTime sentAt) {
        return OutboxMessage.builder()
            .id(UUID.randomUUID().toString()).aggregateType("TEST").aggregateId("tx-sample").eventType("TEST_EVENT")
            .topic("orchestrator").payload("{}").status(status).attempts(0)
            .nextAttemptAt(createdAt).createdAt(createdAt).sentAt(sentAt).build();
    }

}
