package br.com.microservices.orchestrated.productvalidationservice.core.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InboxService {

    private final InboxMessageRepository repository;

    /** @return true se a mensagem é nova e deve ser processada. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean register(String topic, String transactionId, String messageId) {
        return repository.insertIfAbsent(topic, transactionId, messageId, LocalDateTime.now()) == 1;
    }

}
