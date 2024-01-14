package br.com.microservices.choreography.orderservice.core.service;

import br.com.microservices.choreography.orderservice.config.exception.ValidationException;
import br.com.microservices.choreography.orderservice.core.document.Event;
import br.com.microservices.choreography.orderservice.core.repository.EventRepository;
import br.com.microservices.choreography.orderservice.core.dto.EventFilters;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;

@Service
@AllArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository repository;

    public void notifyEnding(Event event) {
        event.setOrderId(event.getPayload().getId());
        event.setCreatedAt(LocalDateTime.now());
        save(event);
        log.info("Order {} with saga notified! TransactionId: {}", event.getOrderId(), event.getTransactionId());
    }

    public List<Event> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Event findByFilters(EventFilters filters) {
        validateEmptyFilters(filters);
        if (!isEmpty(filters.getOrderId())) {
            return findByOrderId(filters.getOrderId());
        } else {
            return findByTransactionId(filters.getTransactionId());
        }
    }

    private Event findByOrderId(String orderId) {
        return repository
            .findTop1ByOrderIdOrderByCreatedAtDesc(orderId)
            .orElseThrow(() -> new ValidationException("Event not found by OrderId"));
    }

    private Event findByTransactionId(String transactionId) {
        return repository
            .findTop1ByTransactionIdOrderByCreatedAtDesc(transactionId)
            .orElseThrow(() -> new ValidationException("Event not found by TransactionId"));
    }

    private void validateEmptyFilters(EventFilters filters) {
        if (isEmpty(filters.getOrderId()) && isEmpty(filters.getTransactionId())) {
            throw new ValidationException("OrderId or TransactionId must be informed.");
        }
    }

    public Event save(Event event) {
        return repository.save(event);
    }
}
