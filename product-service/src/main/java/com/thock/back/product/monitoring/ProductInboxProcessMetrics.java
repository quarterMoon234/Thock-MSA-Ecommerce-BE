package com.thock.back.product.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductInboxProcessMetrics {

    private final Counter claimSucceededCounter;
    private final Counter duplicateIgnoredCounter;
    private final Counter cleanupDeletedCounter;

    public ProductInboxProcessMetrics(MeterRegistry meterRegistry) {
        this.claimSucceededCounter = Counter.builder("product_inbox_claim_succeeded_total")
                .description("Total number of successfully claimed product inbox events")
                .register(meterRegistry);
        this.duplicateIgnoredCounter = Counter.builder("product_inbox_duplicate_ignored_total")
                .description("Total number of duplicate product inbox events ignored")
                .register(meterRegistry);
        this.cleanupDeletedCounter = Counter.builder("product_inbox_cleanup_deleted_total")
                .description("Total number of cleaned up product inbox events")
                .register(meterRegistry);
    }

    public void recordClaimSucceeded() {
        claimSucceededCounter.increment();
    }

    public void recordDuplicateIgnored() {
        duplicateIgnoredCounter.increment();
    }

    public void recordCleanupDeleted(long deletedCount) {
        if (deletedCount > 0) {
            cleanupDeletedCounter.increment(deletedCount);
        }
    }
}
