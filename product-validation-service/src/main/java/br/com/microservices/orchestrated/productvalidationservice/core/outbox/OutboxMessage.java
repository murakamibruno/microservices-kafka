package br.com.microservices.orchestrated.productvalidationservice.core.outbox;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "outbox_message",
    indexes = @Index(name = "idx_outbox_pending", columnList = "status, nextAttemptAt, createdAt"))
public class OutboxMessage {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String headers;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

}
