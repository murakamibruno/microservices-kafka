package br.com.microservices.orchestrated.paymentservice.core.inbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@IdClass(InboxMessageId.class)
@Table(name = "inbox_message")
public class InboxMessage {

    @Id
    private String topic;

    @Id
    private String transactionId;

    private String messageId;

    private LocalDateTime processedAt;

}
