package br.com.microservices.orchestrated.orderservice.core.inbox;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface InboxMessageRepository extends MongoRepository<InboxMessage, String> {
}
