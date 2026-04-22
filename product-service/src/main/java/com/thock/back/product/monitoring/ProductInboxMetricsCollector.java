package com.thock.back.product.monitoring;

import com.thock.back.product.messaging.inbox.ProductInboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductInboxMetricsCollector {

    private final ProductInboxEventRepository productInboxEventRepository;

    private final AtomicLong inboxTotal = new AtomicLong(0);
    private final AtomicLong oldestInboxAgeSeconds = new AtomicLong(0);

    public ProductInboxMetricsCollector(
            ProductInboxEventRepository productInboxEventRepository,
            MeterRegistry meterRegistry
    ) {
        this.productInboxEventRepository = productInboxEventRepository;

        Gauge.builder("product_inbox_total_count", inboxTotal, AtomicLong::get)
                .description("Total number of product inbox events")
                .register(meterRegistry);

        Gauge.builder("product_inbox_oldest_age_seconds", oldestInboxAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest product inbox event")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${product.metrics.collect-interval-ms:10000}")
    public void collect() {
        try {
            long totalCount = productInboxEventRepository.count();
            inboxTotal.set(totalCount);

            LocalDateTime oldestCreatedAt = productInboxEventRepository.findOldestCreatedAt();
            if (oldestCreatedAt == null) {
                oldestInboxAgeSeconds.set(0);
                return;
            }

            long ageSeconds = Duration.between(oldestCreatedAt, LocalDateTime.now()).getSeconds();
            oldestInboxAgeSeconds.set(Math.max(ageSeconds, 0));
        } catch (Exception e) {
            log.warn("Failed to collect product inbox metrics: {}", e.getMessage());
        }
    }
}
