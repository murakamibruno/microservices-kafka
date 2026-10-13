package br.com.microservices.orchestrated.inventoryservice.core.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/** Expurga mensagens SENT após o período de retenção (RF-06). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupJob {

    private final OutboxMessageRepository repository;
    private final OutboxProperties props;

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 * * * *}")
    @Transactional
    public void purgeSent() {
        var cutoff = LocalDateTime.now().minus(Duration.ofHours(props.getCleanup().getRetentionHours()));
        var removed = repository.deleteByStatusAndSentAtBefore(EOutboxStatus.SENT, cutoff);
        log.info("Outbox cleanup removed {} SENT messages older than {}", removed, cutoff);
    }

}
