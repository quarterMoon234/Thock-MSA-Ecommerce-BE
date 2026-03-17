package com.thock.back.product.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductOutboxPublishMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter retryScheduledCounter;
    private final Counter terminalFailedCounter;
    private final Counter cleanupDeletedCounter;

    public ProductOutboxPublishMetrics(MeterRegistry meterRegistry) {
        this.successCounter = Counter.builder("product_outbox_publish_success_total")
                .description("Total number of successful product outbox publishes")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("product_outbox_publish_failure_total")
                .description("Total number of failed product outbox publish attempts")
                .register(meterRegistry);
        this.retryScheduledCounter = Counter.builder("product_outbox_retry_scheduled_total")
                .description("Total number of product outbox retry schedules")
                .register(meterRegistry);
        this.terminalFailedCounter = Counter.builder("product_outbox_terminal_failed_total")
                .description("Total number of product outbox events moved to FAILED")
                .register(meterRegistry);
        this.cleanupDeletedCounter = Counter.builder("product_outbox_cleanup_deleted_total")
                .description("Total number of cleaned up sent product outbox events")
                .register(meterRegistry);
    }

    public void recordSuccess() {
        successCounter.increment();
    }

    public void recordFailure() {
        failureCounter.increment();
    }

    public void recordRetryScheduled() {
        retryScheduledCounter.increment();
    }

    public void recordMovedToFailed() {
        terminalFailedCounter.increment();
    }

    public void recordCleanupDeleted(long deletedCount) {
        if (deletedCount > 0) {
            cleanupDeletedCounter.increment(deletedCount);
        }
    }
}
