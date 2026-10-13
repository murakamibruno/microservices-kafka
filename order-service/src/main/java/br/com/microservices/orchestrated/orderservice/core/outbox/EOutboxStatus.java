package br.com.microservices.orchestrated.orderservice.core.outbox;

public enum EOutboxStatus {

    PENDING,
    PROCESSING,
    SENT,
    FAILED

}
