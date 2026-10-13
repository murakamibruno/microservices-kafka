package br.com.microservices.orchestrated.inventoryservice.core.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxCleanupJobTest {

    @Test
    void purgesSentMessagesOlderThanTheRetention() {
        var repository = mock(OutboxMessageRepository.class);
        var props = new OutboxProperties();
        props.getCleanup().setRetentionHours(24);
        when(repository.deleteByStatusAndSentAtBefore(any(), any())).thenReturn(5L);

        new OutboxCleanupJob(repository, props).purgeSent();

        var cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteByStatusAndSentAtBefore(eq(EOutboxStatus.SENT), cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), LocalDateTime.now()))
            .isBetween(Duration.ofHours(24), Duration.ofHours(24).plusSeconds(5));
    }

}
