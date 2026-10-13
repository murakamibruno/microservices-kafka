package br.com.microservices.orchestrated.productvalidationservice.core.service;

import br.com.microservices.orchestrated.productvalidationservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.Event;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.History;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.productvalidationservice.core.model.Validation;
import br.com.microservices.orchestrated.productvalidationservice.core.inbox.InboxService;
import br.com.microservices.orchestrated.productvalidationservice.core.outbox.OutboxService;
import br.com.microservices.orchestrated.productvalidationservice.core.repository.ProductRepository;
import br.com.microservices.orchestrated.productvalidationservice.core.repository.ValidationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.productvalidationservice.core.enums.ESagaStatus.*;
import static org.springframework.util.ObjectUtils.isEmpty;


@Service
@Slf4j
@RequiredArgsConstructor
public class ProductValidationService {

    private static final String CURRENT_SOURCE="PRODUCT_VALIDATION_SERVICE";

    private final OutboxService outboxService;
    private final InboxService inboxService;
    private final ProductRepository productRepository;
    private final ValidationRepository validationRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${spring.kafka.topic.orchestrator}")
    private String orchestratorTopic;

    public void validateExistingProduct(Event event, String topic, String messageId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (isDuplicate(event, topic, messageId)) {
                    return;
                }
                checkCurrentValidation(event);
                createValidation(event, true);
                handleSuccess(event);
                enqueue(event);
            });
        } catch (Exception ex) {
            log.error("Error trying to validate products: ", ex);
            // A transação de negócio foi revertida (inbox incluso): o evento de falha vai numa nova transação.
            handleFailCurrentNotExecuted(event, ex.getMessage());
            transactionTemplate.executeWithoutResult(status -> {
                if (!isDuplicate(event, topic, messageId)) {
                    enqueue(event);
                }
            });
        }
    }

    private boolean isDuplicate(Event event, String topic, String messageId) {
        if (inboxService.register(topic, event.getTransactionId(), messageId)) {
            return false;
        }
        log.warn("Duplicate message ignored: topic={}, transactionId={}, messageId={}",
            topic, event.getTransactionId(), messageId);
        return true;
    }

    private void enqueue(Event event) {
        outboxService.enqueue(event, orchestratorTopic, "PRODUCT_VALIDATION_" + event.getStatus());
    }

    private void checkCurrentValidation(Event event) {
        validateProductsInformed(event);
        if (validationRepository.existsByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())) {
            throw new ValidationException("There is another transactionId for this validation");
        }
        event.getPayload().getProducts().forEach(product -> {
            validateProductInformed(product);
            validateExistingProduct(product.getProduct().getCode());
        });
    }

    private void validateProductsInformed(Event event) {
        if (isEmpty(event.getPayload()) || isEmpty(event.getPayload().getProducts())) {
            throw new ValidationException("Product List is empty!");
        }

        if (isEmpty(event.getPayload().getId()) || isEmpty(event.getPayload().getTransactionId())) {
            throw new ValidationException("OrderId and TransactionId must be informed!");
        }
    }

    private void validateProductInformed(OrderProducts product) {
        if (isEmpty(product.getProduct()) || isEmpty(product.getProduct().getCode())) {
            throw new ValidationException("Product must be informed");
        }
    }

    private void validateExistingProduct(String code) {
        if (!productRepository.existsByCode(code)) {
            throw new ValidationException("Product does not exists in database");
        }
    }

    private void createValidation(Event event, boolean success) {
        var validation = Validation
            .builder()
            .orderId(event.getPayload().getId())
            .transactionId(event.getTransactionId())
            .success(success)
            .build();
        validationRepository.save(validation);
    }

    private void handleSuccess(Event event) {
        event.setStatus(SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Products validated successfully!");
    }

    private void addHistory(Event event, String message) {
        var history = History
            .builder()
            .source(event.getSource())
            .status(event.getStatus())
            .message(message)
            .createdAt(LocalDateTime.now())
            .build();
        event.addToHistory(history);
    }

    private void handleFailCurrentNotExecuted(Event event, String message) {
        event.setStatus(ROLLBACK_PENDING);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Fail to validate products: ".concat(message));
    }

    @Transactional
    public void rollbackEvent(Event event, String topic, String messageId) {
        if (isDuplicate(event, topic, messageId)) {
            return;
        }
        changeValidationToFail(event);
        event.setStatus(FAIL);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Rollback executed on product validation!");
        enqueue(event);
    }

    private void changeValidationToFail(Event event) {
        validationRepository
            .findByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())
            .ifPresentOrElse(validation -> {
                validation.setSuccess(false);
                validationRepository.save(validation);
            },
            () -> createValidation(event, false));
    }
}
