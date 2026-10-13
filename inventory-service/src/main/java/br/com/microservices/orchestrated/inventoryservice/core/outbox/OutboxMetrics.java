package br.com.microservices.orchestrated.inventoryservice.core.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/** Métricas do outbox/inbox (seção 8 do SDD). */
@Slf4j
@Component
public class OutboxMetrics {

    private final Counter published;
    private final Counter publishFailures;
    private final Counter inboxDuplicates;

    public OutboxMetrics(MeterRegistry registry, OutboxMessageRepository repository) {
        published = Counter.builder("outbox.published.total")
            .description("Mensagens do outbox publicadas no Kafka").register(registry);
        publishFailures = Counter.builder("outbox.publish.failures.total")
            .description("Falhas ao publicar mensagens do outbox").register(registry);
        inboxDuplicates = Counter.builder("inbox.duplicates.total")
            .description("Mensagens duplicadas descartadas pelo inbox").register(registry);
        gauge(registry, "outbox.pending.count", "Mensagens aguardando publicação",
            () -> repository.countByStatus(EOutboxStatus.PENDING));
        gauge(registry, "outbox.failed.count", "Mensagens com tentativas esgotadas (FAILED)",
            () -> repository.countByStatus(EOutboxStatus.FAILED));
        gauge(registry, "outbox.oldest.pending.age.seconds", "Idade da mensagem pendente mais antiga",
            () -> repository.findFirstByStatusOrderByCreatedAtAsc(EOutboxStatus.PENDING)
                .map(m -> (double) Duration.between(m.getCreatedAt(), LocalDateTime.now()).toSeconds())
                .orElse(0.0));
    }

    private static void gauge(MeterRegistry registry, String name, String description, Supplier<Number> supplier) {
        Gauge.builder(name, () -> {
            try {
                return supplier.get().doubleValue();
            } catch (Exception ex) {
                log.warn("Could not read gauge {}: {}", name, ex.getMessage());
                return Double.NaN;
            }
        }).description(description).register(registry);
    }

    public void published() {
        published.increment();
    }

    public void publishFailed() {
        publishFailures.increment();
    }

    public void inboxDuplicate() {
        inboxDuplicates.increment();
    }

}
