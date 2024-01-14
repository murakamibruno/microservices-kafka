package br.com.microservices.choreography.productvalidationservice.core.repository;

import br.com.microservices.choreography.productvalidationservice.core.model.Validation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ValidationRepository extends JpaRepository<Validation, String> {

    Boolean existsByOrderIdAndTransactionId(String orderId, String transactionalId);
    Optional<Validation> findByOrderIdAndTransactionId(String orderId, String transactionalId);
}
