package br.com.microservices.orchestrated.orderservice.core.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private String source;
    private Relay relay = new Relay();

    @Data
    public static class Relay {
        private long pollIntervalMs = 1000;
        private int batchSize = 100;
        private long sendTimeoutMs = 5000;
        private int maxAttempts = 10;
        private long baseBackoffMs = 1000;
        private long maxBackoffMs = 60_000;
        private long leaseMs = 30_000;
    }

}
