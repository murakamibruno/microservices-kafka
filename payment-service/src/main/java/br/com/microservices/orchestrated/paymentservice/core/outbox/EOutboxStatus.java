package br.com.microservices.orchestrated.paymentservice.core.outbox;

public enum EOutboxStatus {

    PENDING,
    SENT,
    FAILED

}
