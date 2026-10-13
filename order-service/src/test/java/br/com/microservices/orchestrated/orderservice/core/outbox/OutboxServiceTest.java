package br.com.microservices.orchestrated.orderservice.core.outbox;

import br.com.microservices.orchestrated.orderservice.core.document.Event;
import br.com.microservices.orchestrated.orderservice.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    @Test
    void enqueueBuildsPendingMessageKeyedByTransactionId() {
        var repository = mock(OutboxMessageRepository.class);
        var jsonUtil = mock(JsonUtil.class);
        var event = Event.builder().transactionId("tx-1").build();
        when(jsonUtil.toJson(event)).thenReturn("{\"transactionId\":\"tx-1\"}");

        new OutboxService(repository, jsonUtil).enqueue(event, "start-saga", "ORDER_CREATED");

        var captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(captor.capture());
        var message = captor.getValue();
        assertThat(message.getId()).isNotNull();
        assertThat(message.getAggregateType()).isEqualTo("ORDER");
        assertThat(message.getAggregateId()).isEqualTo("tx-1");
        assertThat(message.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(message.getTopic()).isEqualTo("start-saga");
        assertThat(message.getPayload()).isEqualTo("{\"transactionId\":\"tx-1\"}");
        assertThat(message.getStatus()).isEqualTo(EOutboxStatus.PENDING);
        assertThat(message.getAttempts()).isZero();
    }

}
