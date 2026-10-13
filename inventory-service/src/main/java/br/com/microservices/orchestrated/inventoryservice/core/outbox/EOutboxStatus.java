package br.com.microservices.orchestrated.inventoryservice.core.outbox;

public enum EOutboxStatus {

    PENDING,
    SENT,
    FAILED

}
