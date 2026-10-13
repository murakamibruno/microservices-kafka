package br.com.microservices.orchestrated.orderservice.core.outbox;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxMessageRepository extends MongoRepository<OutboxMessage, String>, OutboxMessageClaimRepository {
}
