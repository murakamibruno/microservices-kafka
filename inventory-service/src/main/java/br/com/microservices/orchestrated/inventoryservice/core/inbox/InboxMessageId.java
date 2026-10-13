package br.com.microservices.orchestrated.inventoryservice.core.inbox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InboxMessageId implements Serializable {

    private String topic;
    private String transactionId;

}
