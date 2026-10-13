package br.com.microservices.orchestrated.orderservice.core.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class OutboxMessageRepositoryImpl implements OutboxMessageClaimRepository {

    private final MongoOperations mongo;

    @Override
    public Optional<OutboxMessage> claimNext(LocalDateTime now, LocalDateTime lockedUntil, String instanceId) {
        var query = new Query(new Criteria().orOperator(
            Criteria.where("status").is(EOutboxStatus.PENDING).and("nextAttemptAt").lte(now),
            Criteria.where("status").is(EOutboxStatus.PROCESSING).and("lockedUntil").lt(now)))
            .with(Sort.by(Sort.Direction.ASC, "createdAt"));
        var update = new Update()
            .set("status", EOutboxStatus.PROCESSING)
            .set("lockedUntil", lockedUntil)
            .set("lockedBy", instanceId);
        return Optional.ofNullable(
            mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), OutboxMessage.class));
    }

}
