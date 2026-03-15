package com.thock.back.product.monitoring;

import com.thock.back.product.messaging.outbox.ProductOutboxEventRepository;
import com.thock.back.product.messaging.outbox.ProductOutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductOutboxMetricsCollector {

    private final ProductOutboxEventRepository productOutboxEventRepository;

    private final AtomicLong outboxTotal = new AtomicLong(0);
    private final AtomicLong outboxPendingRatioPercent = new AtomicLong(0);
    private final Map<ProductOutboxStatus, AtomicLong> outboxStatusCount = new EnumMap<>(ProductOutboxStatus.class);
    private final Map<ProductOutboxStatus, AtomicLong> outboxStatusRatioPercent = new EnumMap<>(ProductOutboxStatus.class);

    public ProductOutboxMetricsCollector(
            ProductOutboxEventRepository productOutboxEventRepository,
            MeterRegistry meterRegistry
    ) {
        this.productOutboxEventRepository = productOutboxEventRepository;

        Gauge.builder("product_outbox_total_count", outboxTotal, AtomicLong::get)
                .description("Total number of product outbox events")
                .register(meterRegistry);

        Gauge.builder("product_outbox_pending_ratio_percent", outboxPendingRatioPercent, AtomicLong::get)
                .description("Pending outbox ratio percent")
                .register(meterRegistry);

        for (ProductOutboxStatus status : ProductOutboxStatus.values()) {
            AtomicLong countGauge = new AtomicLong(0);
            AtomicLong ratioGauge = new AtomicLong(0);
            outboxStatusCount.put(status, countGauge);
            outboxStatusRatioPercent.put(status, ratioGauge);

            Gauge.builder("product_outbox_status_count", countGauge, AtomicLong::get)
                    .description("Product outbox event count by status")
                    .tag("status", status.name())
                    .register(meterRegistry);

            Gauge.builder("product_outbox_status_ratio_percent", ratioGauge, AtomicLong::get)
                    .description("Product outbox event ratio percent by status")
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${product.metrics.collect-interval-ms:10000}")
    public void collect() {
        try {
            Map<ProductOutboxStatus, Long> counts = new EnumMap<>(ProductOutboxStatus.class);
            long totalOutbox = 0;

            for (ProductOutboxStatus status : ProductOutboxStatus.values()) {
                long count = productOutboxEventRepository.countByStatus(status);
                counts.put(status, count);
                outboxStatusCount.get(status).set(count);
                totalOutbox += count;
            }

            outboxTotal.set(totalOutbox);

            long pending = counts.getOrDefault(ProductOutboxStatus.PENDING, 0L);
            outboxPendingRatioPercent.set(totalOutbox == 0 ? 0 : (pending * 100) / totalOutbox);

            for (ProductOutboxStatus status : ProductOutboxStatus.values()) {
                long count = counts.getOrDefault(status, 0L);
                long ratio = totalOutbox == 0 ? 0 : (count * 100) / totalOutbox;
                outboxStatusRatioPercent.get(status).set(ratio);
            }
        } catch (Exception e) {
            log.warn("Failed to collect product outbox metrics: {}", e.getMessage());
        }
    }
}
