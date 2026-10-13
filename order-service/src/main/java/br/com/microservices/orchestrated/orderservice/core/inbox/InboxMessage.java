package br.com.microservices.orchestrated.orderservice.core.inbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "inbox")
public class InboxMessage {

    /** topic + ":" + transactionId (ADR-04). */
    @Id
    private String id;
    private String topic;
    private String transactionId;
    private String messageId;
    private LocalDateTime processedAt;

}
