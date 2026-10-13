package br.com.microservices.orchestrated.inventoryservice.core.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, InboxMessageId> {

    @Modifying
    @Query(value = """
        INSERT INTO inbox_message (topic, transaction_id, message_id, processed_at)
        VALUES (:topic, :transactionId, :messageId, :now)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("topic") String topic,
                       @Param("transactionId") String transactionId,
                       @Param("messageId") String messageId,
                       @Param("now") LocalDateTime now);

}
