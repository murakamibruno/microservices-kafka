package br.com.microservices.orchestrated.orchestratorservice.core.service;

import br.com.microservices.orchestrated.orchestratorservice.core.dto.Event;
import br.com.microservices.orchestrated.orchestratorservice.core.dto.History;
import br.com.microservices.orchestrated.orchestratorservice.core.enums.ETopics;
import br.com.microservices.orchestrated.orchestratorservice.core.producer.SagaOrchestratorProducer;
import br.com.microservices.orchestrated.orchestratorservice.core.saga.SagaExecutionController;
import br.com.microservices.orchestrated.orchestratorservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.orchestratorservice.core.enums.EEventSource.*;
import static br.com.microservices.orchestrated.orchestratorservice.core.enums.ESagaStatus.*;
import static br.com.microservices.orchestrated.orchestratorservice.core.enums.ETopics.*;

@AllArgsConstructor
@Service
@Slf4j
public class OrchestratorService {

    private final SagaOrchestratorProducer producer;
    private final JsonUtil jsonUtil;
    private final SagaExecutionController sagaExecutionController;

    public void startSaga(Event event, String messageId) {
        event.setSource(ORCHESTRATOR);
        event.setStatus(SUCCESS);
        var topic = getTopic(event);
        log.info("SAGA STARTED!");
        addHistory(event, "Saga Started!!");
        sendToProducerWithEvent(event, topic, messageId);
    }

    private ETopics getTopic(Event event) {
        return sagaExecutionController.getNextTopic(event);
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

    public void finishSagaSuccess(Event event, String messageId) {
        event.setSource(ORCHESTRATOR);
        event.setStatus(SUCCESS);
        log.info("SAGA FINISHED SUCCESSFULLY FOR EVENT {}!", event.getId());
        addHistory(event, "Saga finished successfully!!");
        notifyFinishedSaga(event, messageId);
    }

    public void finishSagaFail(Event event, String messageId) {
        event.setSource(ORCHESTRATOR);
        event.setStatus(FAIL);
        log.info("SAGA FINISHED WITH ERRORS FOR EVENT {}!", event.getId());
        addHistory(event, "Saga finished with errors!!");
        notifyFinishedSaga(event, messageId);
    }

    private void notifyFinishedSaga(Event event, String messageId) {
        send(event, NOTIFY_ENDING.getTopic(), messageId);
    }

    public void continueSaga(Event event, String messageId) {
        var topic = getTopic(event);
        log.info("SAGA CONTINUED FOR EVENT {}", event.getId());
        sendToProducerWithEvent(event, topic, messageId);
    }

    private void sendToProducerWithEvent(Event event, ETopics topic, String messageId) {
        send(event, topic.getTopic(), messageId);
    }

    private void send(Event event, String topic, String messageId) {
        // Sem x-message-id de entrada (mensagem legada), usa o transactionId: cada tópico é visitado uma vez por saga.
        var inputMessageId = messageId != null ? messageId : event.getTransactionId();
        producer.sendEvent(jsonUtil.toJson(event), topic, event.getTransactionId(), inputMessageId);
    }
}
