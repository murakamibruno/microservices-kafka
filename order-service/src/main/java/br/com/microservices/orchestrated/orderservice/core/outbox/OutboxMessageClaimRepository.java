package br.com.microservices.orchestrated.orderservice.core.outbox;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OutboxMessageClaimRepository {

    /**
     * Reivindica atomicamente (findAndModify) a próxima mensagem elegível, marcando-a como
     * PROCESSING com um lease. Mensagens PROCESSING com lease vencido voltam a ser elegíveis.
     */
    Optional<OutboxMessage> claimNext(LocalDateTime now, LocalDateTime lockedUntil, String instanceId);

}
