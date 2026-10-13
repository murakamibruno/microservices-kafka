package br.com.microservices.orchestrated.inventoryservice.core.service;

import br.com.microservices.orchestrated.inventoryservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.inventoryservice.core.dto.Event;
import br.com.microservices.orchestrated.inventoryservice.core.dto.History;
import br.com.microservices.orchestrated.inventoryservice.core.dto.Order;
import br.com.microservices.orchestrated.inventoryservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.inventoryservice.core.model.Inventory;
import br.com.microservices.orchestrated.inventoryservice.core.model.OrderInventory;
import br.com.microservices.orchestrated.inventoryservice.core.inbox.InboxService;
import br.com.microservices.orchestrated.inventoryservice.core.outbox.OutboxService;
import br.com.microservices.orchestrated.inventoryservice.core.repository.InventoryRepository;
import br.com.microservices.orchestrated.inventoryservice.core.repository.OrderInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.inventoryservice.core.enums.ESagaStatus.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

    private static final String CURRENT_SOURCE="INVENTORY_SERVICE";

    private final OutboxService outboxService;
    private final InboxService inboxService;
    private final InventoryRepository inventoryRepository;
    private final OrderInventoryRepository orderInventoryRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${spring.kafka.topic.orchestrator}")
    private String orchestratorTopic;

    public void updateInventory(Event event, String topic, String messageId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (isDuplicate(event, topic, messageId)) {
                    return;
                }
                checkCurrentValidation(event);
                createOrderInventory(event);
                updateInventory(event.getPayload());
                handleSuccess(event);
                enqueue(event);
            });
        } catch (Exception ex) {
            log.error("Error trying to update inventory: ", ex);
            // A transação de negócio foi revertida (inbox incluso): o evento de falha vai numa nova transação.
            handleFailCurrentNotExecuted(event, ex.getMessage());
            enqueueOnce(event, topic, messageId);
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
        outboxService.enqueue(event, orchestratorTopic, "INVENTORY_" + event.getStatus());
    }

    private void enqueueOnce(Event event, String topic, String messageId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (!isDuplicate(event, topic, messageId)) {
                enqueue(event);
            }
        });
    }

    private void checkCurrentValidation(Event event) {
        if (orderInventoryRepository.existsByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())) {
            throw new ValidationException("There is another transactionId for this validation");
        }
    }

    private void createOrderInventory(Event event) {
        event
            .getPayload()
            .getProducts()
            .forEach(product -> {
                var inventory = findInventoryByProductCode(product.getProduct().getCode());
                var orderInventory = createOrderInventory(event, product, inventory);
                orderInventoryRepository.save(orderInventory);
            });
    }

    private OrderInventory createOrderInventory(Event event, OrderProducts product, Inventory inventory) {
        return OrderInventory
            .builder()
            .inventory(inventory)
            .oldQuantity(inventory.getAvailable())
            .orderQuantity(product.getQuantity())
            .newQuantity(inventory.getAvailable() - product.getQuantity())
            .orderId(event.getPayload().getId())
            .transactionId(event.getTransactionId())
            .build();
    }

    private Inventory findInventoryByProductCode(String productCode) {
        return inventoryRepository
            .findByProductCode(productCode)
            .orElseThrow(() -> new ValidationException("Inventory not found by informed product"));
    }

    private void updateInventory(Order order) {
        order
            .getProducts()
            .forEach(product -> {
                var inventory = findInventoryByProductCode(product.getProduct().getCode());
                if (inventoryRepository.decrementIfAvailable(inventory.getId(), product.getQuantity()) == 0) {
                    throw new ValidationException("Product is out of stock");
                }
            });
    }

    private void handleSuccess(Event event) {
        event.setStatus(SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Inventory updated successfully!");
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
        addHistory(event, "Fail to update inventory: ".concat(message));
    }

    public void rollbackInventory(Event event, String topic, String messageId) {
        event.setStatus(FAIL);
        event.setSource(CURRENT_SOURCE);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (isDuplicate(event, topic, messageId)) {
                    return;
                }
                returnInventoryToPreviousValues(event);
                addHistory(event, "Rollback executed for inventory!");
                enqueue(event);
            });
        } catch (Exception ex) {
            log.error("Error trying to rollback inventory: ", ex);
            addHistory(event, "Rollback not executed for inventory: ".concat(String.valueOf(ex.getMessage())));
            enqueueOnce(event, topic, messageId);
        }
    }

    private void returnInventoryToPreviousValues(Event event) {
        orderInventoryRepository
            .findByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())
            .forEach(orderInventory -> {
                inventoryRepository.increment(orderInventory.getInventory().getId(), orderInventory.getOrderQuantity());
                log.info("Restored {} unit(s) of inventory {} for order {}",
                    orderInventory.getOrderQuantity(), orderInventory.getInventory().getId(), event.getPayload().getId());
            });
    }
}
