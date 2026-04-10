package com.thock.back.product.monitoring;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;


@Component
public class ProductCacheMetrics {

    private final Counter detailHitCounter;
    private final Counter detailMissCounter;
    private final Counter internalHitCounter;
    private final Counter internalMissCounter;
    private final Counter internalHitItemCounter;
    private final Counter internalMissItemCounter;

    public ProductCacheMetrics(MeterRegistry meterRegistry) {
        this.detailHitCounter = Counter.builder("product_cache_detail_hit_total")
                .description("Total number of product detail cache hits")
                .register(meterRegistry);

        this.detailMissCounter = Counter.builder("product_cache_detail_miss_total")
                .description("Total number of product detail cache misses")
                .register(meterRegistry);

        this.internalHitCounter = Counter.builder("product_cache_internal_hit_total")
                .description("Total number of product internal list cache hits")
                .register(meterRegistry);

        this.internalMissCounter = Counter.builder("product_cache_internal_miss_total")
                .description("Total number of product internal list cache misses")
                .register(meterRegistry);

        this.internalHitItemCounter = Counter.builder("product_cache_internal_hit_item_total")
                .description("Total number of cached items returned from internal product list requests")
                .register(meterRegistry);

        this.internalMissItemCounter = Counter.builder("product_cache_internal_miss_item_total")
                .description("Total number of missed items from internal product list requests")
                .register(meterRegistry);
    }

    public void recordDetailHit() {
        detailHitCounter.increment();
    }

    public void recordDetailMiss() {
        detailMissCounter.increment();
    }

    public void recordInternalHit(int hitCount) {
        if (hitCount > 0) {
            internalHitCounter.increment();
            internalHitItemCounter.increment(hitCount);
        }
    }

    public void recordInternalMiss(int missCount) {
        if (missCount > 0) {
            internalMissCounter.increment();
            internalMissItemCounter.increment(missCount);
        }
    }
}
