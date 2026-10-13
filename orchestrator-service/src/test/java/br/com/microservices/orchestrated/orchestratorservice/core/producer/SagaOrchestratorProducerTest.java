package br.com.microservices.orchestrated.orchestratorservice.core.producer;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SagaOrchestratorProducerTest {

    private KafkaProducer<String, String> kafkaProducer;
    private SagaOrchestratorProducer producer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaProducer = mock(KafkaProducer.class);
        producer = new SagaOrchestratorProducer(kafkaProducer);
        ReflectionTestUtils.setField(producer, "sendTimeoutMs", 1000L);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sendAndCapture(String inputMessageId, String topic) {
        when(kafkaProducer.send(any())).thenReturn(CompletableFuture.completedFuture((RecordMetadata) null));
        producer.sendEvent("{}", topic, "tx-1", inputMessageId);
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaProducer, atLeastOnce()).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value());
    }

    @Test
    void publishesWithTransactionIdAsKeyAndTraceHeaders() {
        var record = sendAndCapture("in-1", "payment-success");

        assertThat(record.topic()).isEqualTo("payment-success");
        assertThat(record.key()).isEqualTo("tx-1");
        assertThat(header(record, "x-transaction-id")).isEqualTo("tx-1");
        assertThat(header(record, "x-source")).isEqualTo("ORCHESTRATOR");
        assertThat(header(record, "x-message-id")).isNotBlank();
    }

    @Test
    void messageIdIsDeterministicPerInputMessageAndTargetTopic() {
        var first = header(sendAndCapture("in-1", "payment-success"), "x-message-id");
        var again = header(sendAndCapture("in-1", "payment-success"), "x-message-id");
        var otherTopic = header(sendAndCapture("in-1", "payment-fail"), "x-message-id");
        var otherInput = header(sendAndCapture("in-2", "payment-success"), "x-message-id");

        assertThat(again).isEqualTo(first);
        assertThat(otherTopic).isNotEqualTo(first);
        assertThat(otherInput).isNotEqualTo(first);
    }

    @Test
    void sendFailureIsPropagated() {
        when(kafkaProducer.send(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> producer.sendEvent("{}", "payment-success", "tx-1", "in-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("payment-success");
    }

}
