package br.com.microservices.orchestrated.orchestratorservice.core.consumer;

import br.com.microservices.orchestrated.orchestratorservice.core.dto.Event;
import br.com.microservices.orchestrated.orchestratorservice.core.service.OrchestratorService;
import br.com.microservices.orchestrated.orchestratorservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class SagaOrchestratorConsumer {

    private final JsonUtil jsonUtil;
    private final OrchestratorService orchestratorService;

    @KafkaListener(
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${spring.kafka.topic.start-saga}"
    )
    public void consumeStartSagaEvent(@Payload String payload,
                                      @Header(name = "x-message-id", required = false) String messageId) {
        log.info("Receiving event from {} from start-saga topic", payload);
        orchestratorService.startSaga(parse(payload), messageId);
    }

    @KafkaListener(
        groupId = "${spring.kafka.consumer.group-id}",
        topics = "${spring.kafka.topic.orchestrator}"
    )
    public void consumeOrchestratorEvent(@Payload String payload,
                                         @Header(name = "x-message-id", required = false) String messageId) {
        log.info("Receiving event from {} from orchestrator topic", payload);
        orchestratorService.continueSaga(parse(payload), messageId);
    }

    @KafkaListener(
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${spring.kafka.topic.finish-success}"
    )
    public void consumeFinishSuccessEvent(@Payload String payload,
                                          @Header(name = "x-message-id", required = false) String messageId) {
        log.info("Receiving event from {} from finish-success topic", payload);
        orchestratorService.finishSagaSuccess(parse(payload), messageId);
    }

    @KafkaListener(
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${spring.kafka.topic.finish-fail}"
    )
    public void consumeFinishFailEvent(@Payload String payload,
                                       @Header(name = "x-message-id", required = false) String messageId) {
        log.info("Receiving event from {} from finish-fail topic", payload);
        orchestratorService.finishSagaFail(parse(payload), messageId);
    }

    /** Payload inválido não adianta reprocessar: vai direto para a DLT (IllegalArgumentException é não-retentável). */
    private Event parse(String payload) {
        var event = jsonUtil.toEvent(payload);
        if (event == null) {
            throw new IllegalArgumentException("Invalid event payload");
        }
        return event;
    }

}
