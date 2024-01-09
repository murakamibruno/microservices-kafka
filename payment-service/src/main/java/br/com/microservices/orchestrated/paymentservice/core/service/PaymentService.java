package br.com.microservices.orchestrated.paymentservice.core.service;

import br.com.microservices.orchestrated.paymentservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.paymentservice.core.dto.Event;
import br.com.microservices.orchestrated.paymentservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.paymentservice.core.model.Payment;
import br.com.microservices.orchestrated.paymentservice.core.producer.PaymentProducer;
import br.com.microservices.orchestrated.paymentservice.core.repository.PaymentRepository;
import br.com.microservices.orchestrated.paymentservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE="PAYMENT_SERVICE";
    private static final Double REDUCE_SUM_VALUE = 0.0;

    private final JsonUtil jsonUtil;
    private final PaymentProducer producer;
    private final PaymentRepository paymentRepository;

    public void realizePayment(Event event) {
        try {
            checkCurrentPayment(event);
            createPendingPayment(event);
        } catch(Exception ex) {
            log.error("Error trying to realize payment: ", ex);
        }
        producer.sendEvent(jsonUtil.toJson(event));
    }

    private void checkCurrentPayment(Event event) {
        if (paymentRepository.existsByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())) {
            throw new ValidationException("There is another transactionId for this payment");
        }
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
}
