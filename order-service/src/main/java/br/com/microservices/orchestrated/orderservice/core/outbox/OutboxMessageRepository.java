package br.com.microservices.orchestrated.orderservice.core.outbox;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface OutboxMessageRepository extends MongoRepository<OutboxMessage, String>, OutboxMessageClaimRepository {

    long countByStatus(EOutboxStatus status);

    long countByStatusIn(Collection<EOutboxStatus> statuses);

    Optional<OutboxMessage> findFirstByStatusInOrderByCreatedAtAsc(Collection<EOutboxStatus> statuses);

    long deleteByStatusAndSentAtBefore(EOutboxStatus status, LocalDateTime cutoff);

}
