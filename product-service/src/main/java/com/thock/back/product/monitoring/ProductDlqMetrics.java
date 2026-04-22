package com.thock.back.product.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductDlqMetrics {

    private final Counter retryAttemptCounter;
    private final Counter dlqPublishedCounter;
    private final Counter nonRetryableCounter;
    private final Counter retryExhaustedCounter;

    public ProductDlqMetrics(MeterRegistry meterRegistry) {
        this.retryAttemptCounter = Counter.builder("product_dlq_retry_attempt_total")
                .description("Total number of DLQ retry attempts")
                .register(meterRegistry);

        this.dlqPublishedCounter = Counter.builder("product_dlq_published_total")
                .description("Total number of messages published to product DLQ")
                .register(meterRegistry);

        this.nonRetryableCounter = Counter.builder("product_dlq_non_retryable_total")
                .description("Total number of non-retryable messages sent to product DLQ")
                .register(meterRegistry);

        this.retryExhaustedCounter = Counter.builder("product_dlq_retry_exhausted_total")
                .description("Total number of retry-exhausted messages sent to product DLQ")
                .register(meterRegistry);
    }

    public void recordRetryAttempt() {
        retryAttemptCounter.increment();
    }

    public void recordDlqPublished() {
        dlqPublishedCounter.increment();
    }

    public void recordNonRetryable() {
        nonRetryableCounter.increment();
    }

    public void recordRetryExhausted() {
        retryExhaustedCounter.increment();
    }
}
