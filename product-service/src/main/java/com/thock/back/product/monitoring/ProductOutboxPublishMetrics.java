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

    public ProductOutboxPublishMetrics(MeterRegistry meterRegistry) {
        this.successCounter = Counter.builder("product_outbox_publish_success_total")
                .description("Total number of successful product outbox publishes")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("product_outbox_publish_failure_total")
                .description("Total number of failed product outbox publishes")
                .register(meterRegistry);
    }

    public void recordSuccess() {
        successCounter.increment();
    }

    public void recordFailure() {
        failureCounter.increment();
    }
}
