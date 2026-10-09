package br.com.microservices.orchestrated.paymentservice.core.service;

import br.com.microservices.orchestrated.paymentservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.paymentservice.core.dto.Event;
import br.com.microservices.orchestrated.paymentservice.core.dto.History;
import br.com.microservices.orchestrated.paymentservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.paymentservice.core.enums.EPaymentStatus;
import br.com.microservices.orchestrated.paymentservice.core.model.Payment;
import br.com.microservices.orchestrated.paymentservice.core.inbox.InboxService;
import br.com.microservices.orchestrated.paymentservice.core.outbox.OutboxService;
import br.com.microservices.orchestrated.paymentservice.core.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.paymentservice.core.enums.ESagaStatus.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE="PAYMENT_SERVICE";
    private static final Double REDUCE_SUM_VALUE = 0.0;
    private static final Double MIN_AMOUNT_VALUE = 0.1;

    private final OutboxService outboxService;
    private final InboxService inboxService;
    private final PaymentRepository paymentRepository;

    @Value("${spring.kafka.topic.orchestrator}")
    private String orchestratorTopic;

    @Transactional
    public void realizePayment(Event event, String topic, String messageId) {
        if (isDuplicate(event, topic, messageId)) {
            return;
        }
        try {
            createPendingPayment(event);
            var payment = findByOrderIdAndTransactionId(event);
            validateAmount(payment.getTotalAmount());
            changePaymentToSuccess(payment);
            handleSuccess(event);
        } catch (ValidationException ex) {
            log.error("Error trying to realize payment: ", ex);
            handleFailCurrentNotExecuted(event, ex.getMessage());
        }
        enqueue(event);
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
        outboxService.enqueue(event, orchestratorTopic, "PAYMENT_" + event.getStatus());
    }

    private void createPendingPayment(Event event) {
        var totalAmount = calculateAmount(event);
        var totalItems = calculateTotalItems(event);
        var payment = Payment
            .builder()
            .orderId(event.getPayload().getId())
            .transactionId(event.getTransactionId())
            .totalAmount(totalAmount)
            .totalItems(totalItems)
            .build();
        save(payment);
        setEventAmountItems(event,payment);
    }

    private double calculateAmount(Event event) {
        return event
            .getPayload()
            .getProducts()
            .stream()
            .map(product -> product.getQuantity() * product.getProduct().getUnitValue())
            .reduce(REDUCE_SUM_VALUE, Double::sum);
    }

    private int calculateTotalItems(Event event) {
        return event
            .getPayload()
            .getProducts()
            .stream()
            .map(OrderProducts::getQuantity)
            .reduce(REDUCE_SUM_VALUE.intValue(), Integer::sum);
    }

    private void save(Payment payment) {
        paymentRepository.save(payment);
    }

    private void setEventAmountItems(Event event, Payment payment) {
        event.getPayload().setTotalAmount(payment.getTotalAmount());
        event.getPayload().setTotalItems(payment.getTotalItems());
    }

    private Payment findByOrderIdAndTransactionId(Event event) {
        return paymentRepository
            .findByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())
            .orElseThrow(() -> new ValidationException("Payment not found by orderId and TransactionId"));
    }

    private void validateAmount(Double amount) {
        if (amount < 0.1) {
            throw new ValidationException("The minimum amount available is ".concat(MIN_AMOUNT_VALUE.toString()));
        }
    }

    private void changePaymentToSuccess(Payment payment) {
        payment.setStatus(EPaymentStatus.SUCCESS);
        save(payment);
    }

    private void handleSuccess(Event event) {
        event.setStatus(SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Payment realized successfully!");
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
        addHistory(event, "Fail to execute payment: ".concat(message));
    }

    @Transactional
    public void realizeRefund(Event event, String topic, String messageId) {
        if (isDuplicate(event, topic, messageId)) {
            return;
        }
        event.setStatus(FAIL);
        event.setSource(CURRENT_SOURCE);
        try {
            changePaymentStatusToRefund(event);
            addHistory(event, "Rollback executed on payment!");
        } catch (ValidationException ex) {
            addHistory(event, "Rollback not executed on payment: ".concat(ex.getMessage()));
        }
        enqueue(event);
    }

    private void changePaymentStatusToRefund(Event event) {
        var payment = findByOrderIdAndTransactionId(event);
        payment.setStatus(EPaymentStatus.REFUND);
        setEventAmountItems(event, payment);
        save(payment);
    }

}
