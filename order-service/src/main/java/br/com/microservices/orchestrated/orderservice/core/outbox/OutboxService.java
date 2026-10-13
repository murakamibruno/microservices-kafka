package br.com.microservices.orchestrated.orderservice.core.outbox;

import br.com.microservices.orchestrated.orderservice.core.document.Event;
import br.com.microservices.orchestrated.orderservice.core.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OutboxMessageRepository repository;
    private final JsonUtil jsonUtil;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Event event, String topic, String eventType) {
        var now = LocalDateTime.now();
        repository.save(OutboxMessage.builder()
            .id(UUID.randomUUID().toString())
            .aggregateType(AGGREGATE_TYPE)
            .aggregateId(event.getTransactionId())
            .eventType(eventType)
            .topic(topic)
            .payload(jsonUtil.toJson(event))
            .status(EOutboxStatus.PENDING)
            .nextAttemptAt(now)
            .createdAt(now)
            .build());
    }

}
