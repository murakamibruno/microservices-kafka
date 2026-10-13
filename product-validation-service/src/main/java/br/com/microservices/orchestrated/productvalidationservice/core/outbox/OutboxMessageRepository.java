package br.com.microservices.orchestrated.productvalidationservice.core.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query(value = """
        SELECT * FROM outbox_message
         WHERE status = 'PENDING' AND next_attempt_at <= :now
         ORDER BY created_at
         LIMIT :batchSize
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxMessage> lockNextBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    long countByStatus(EOutboxStatus status);

    Optional<OutboxMessage> findFirstByStatusOrderByCreatedAtAsc(EOutboxStatus status);

    long deleteByStatusAndSentAtBefore(EOutboxStatus status, LocalDateTime cutoff);

}
