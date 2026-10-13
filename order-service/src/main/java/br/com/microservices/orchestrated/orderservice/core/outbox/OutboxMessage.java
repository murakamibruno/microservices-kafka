package br.com.microservices.orchestrated.orderservice.core.outbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "outbox")
@CompoundIndex(name = "idx_outbox_pending", def = "{'status': 1, 'nextAttemptAt': 1, 'createdAt': 1}")
public class OutboxMessage {

    @Id
    private String id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String topic;
    private String payload;
    private String headers;
    private EOutboxStatus status;
    private int attempts;
    private String lastError;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime lockedUntil;
    private String lockedBy;

}
