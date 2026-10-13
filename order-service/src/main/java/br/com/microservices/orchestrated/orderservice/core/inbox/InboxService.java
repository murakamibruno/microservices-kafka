package br.com.microservices.orchestrated.orderservice.core.inbox;

import br.com.microservices.orchestrated.orderservice.core.outbox.OutboxMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InboxService {

    private final MongoTemplate mongo;
    private final OutboxMetrics metrics;

    /**
     * Upsert com $setOnInsert: se o documento já existe nada é escrito. Evita o insert com
     * DuplicateKeyException, que abortaria a transação do Mongo.
     *
     * @return true se a mensagem é nova e deve ser processada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean register(String topic, String transactionId, String messageId) {
        var id = topic + ":" + transactionId;
        var result = mongo.upsert(
            Query.query(Criteria.where("_id").is(id)),
            new Update()
                .setOnInsert("topic", topic)
                .setOnInsert("transactionId", transactionId)
                .setOnInsert("messageId", messageId)
                .setOnInsert("processedAt", LocalDateTime.now()),
            InboxMessage.class);
        var isNew = result.getUpsertedId() != null;
        if (!isNew) {
            metrics.inboxDuplicate();
        }
        return isNew;
    }

}
