package com.thock.back.product.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.thock.back.product.stock.ProductStockRedisReserveResult;

@Component
@ConditionalOnProperty(prefix = "product.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductStockReservationPressureMetrics {

    private final AtomicLong redisPreRejectedCount = new AtomicLong(0);
    private final AtomicLong dbEntryCount = new AtomicLong(0);
    private final AtomicLong dbSucceededCount = new AtomicLong(0);
    private final AtomicLong dbRejectedCount = new AtomicLong(0);
    private final AtomicLong dbFailedCount = new AtomicLong(0);
    private final AtomicInteger dbActive = new AtomicInteger(0);
    private final AtomicInteger dbActiveMax = new AtomicInteger(0);
    private final AtomicLong dbDurationTotalNanos = new AtomicLong(0);
    private final AtomicLong dbDurationMaxNanos = new AtomicLong(0);
    private final AtomicLong redisCompensationCount = new AtomicLong(0);
    private final AtomicLong redisReservedCount = new AtomicLong(0);
    private final AtomicLong redisAlreadyReservedCount = new AtomicLong(0);
    private final AtomicLong redisOutOfStockCount = new AtomicLong(0);
    private final AtomicLong redisStockKeyMissingCount = new AtomicLong(0);
    private final AtomicLong redisInvalidArgumentCount = new AtomicLong(0);
    private final AtomicLong redisDisabledCount = new AtomicLong(0);
    private final AtomicLong redisUnavailableCount = new AtomicLong(0);

    public ProductStockReservationPressureMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("product_stock_reserve_redis_pre_rejected_count", redisPreRejectedCount, AtomicLong::get)
                .description("Number of reserve requests rejected by Redis before entering the DB lock path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_entry_count", dbEntryCount, AtomicLong::get)
                .description("Number of reserve requests that entered the DB pessimistic-lock path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_succeeded_count", dbSucceededCount, AtomicLong::get)
                .description("Number of reserve requests completed successfully in the DB path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_rejected_count", dbRejectedCount, AtomicLong::get)
                .description("Number of reserve requests rejected after entering the DB path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_failed_count", dbFailedCount, AtomicLong::get)
                .description("Number of reserve requests that failed unexpectedly in the DB path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_active", dbActive, AtomicInteger::get)
                .description("Current number of reserve requests inside the DB path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_active_max", dbActiveMax, AtomicInteger::get)
                .description("Peak concurrent reserve requests inside the DB path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_duration_avg_millis", this, ProductStockReservationPressureMetrics::averageDurationMillis)
                .description("Average duration in milliseconds spent in the DB reserve path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_db_duration_max_millis", this, ProductStockReservationPressureMetrics::maxDurationMillis)
                .description("Maximum duration in milliseconds spent in the DB reserve path")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_compensation_count", redisCompensationCount, AtomicLong::get)
                .description("Number of Redis reservation compensations after DB failure")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_reserved_count", redisReservedCount, AtomicLong::get)
                .description("Number of reserve requests that successfully reserved stock in Redis")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_already_reserved_count", redisAlreadyReservedCount, AtomicLong::get)
                .description("Number of reserve requests that found an existing Redis reservation")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_out_of_stock_count", redisOutOfStockCount, AtomicLong::get)
                .description("Number of reserve requests rejected as out of stock by Redis")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_stock_key_missing_count", redisStockKeyMissingCount, AtomicLong::get)
                .description("Number of reserve requests that fell back because the Redis stock key was missing")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_invalid_argument_count", redisInvalidArgumentCount, AtomicLong::get)
                .description("Number of reserve requests rejected due to invalid Redis reserve arguments")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_disabled_count", redisDisabledCount, AtomicLong::get)
                .description("Number of reserve requests processed while Redis stock gating was disabled")
                .register(meterRegistry);

        Gauge.builder("product_stock_reserve_redis_unavailable_count", redisUnavailableCount, AtomicLong::get)
                .description("Number of reserve requests that fell back because Redis was unavailable")
                .register(meterRegistry);
    }

    public void reset() {
        redisPreRejectedCount.set(0);
        dbEntryCount.set(0);
        dbSucceededCount.set(0);
        dbRejectedCount.set(0);
        dbFailedCount.set(0);
        dbActive.set(0);
        dbActiveMax.set(0);
        dbDurationTotalNanos.set(0);
        dbDurationMaxNanos.set(0);
        redisCompensationCount.set(0);
        redisReservedCount.set(0);
        redisAlreadyReservedCount.set(0);
        redisOutOfStockCount.set(0);
        redisStockKeyMissingCount.set(0);
        redisInvalidArgumentCount.set(0);
        redisDisabledCount.set(0);
        redisUnavailableCount.set(0);
    }

    public void recordRedisPreRejected() {
        redisPreRejectedCount.incrementAndGet();
    }

    public long recordDbEntryStart() {
        dbEntryCount.incrementAndGet();
        int active = dbActive.incrementAndGet();
        updateMax(dbActiveMax, active);
        return System.nanoTime();
    }

    public void recordDbSucceeded() {
        dbSucceededCount.incrementAndGet();
    }

    public void recordDbRejected() {
        dbRejectedCount.incrementAndGet();
    }

    public void recordDbFailed() {
        dbFailedCount.incrementAndGet();
    }

    public void recordDbEntryFinish(long startedAtNanos) {
        long durationNanos = Math.max(System.nanoTime() - startedAtNanos, 0);
        dbDurationTotalNanos.addAndGet(durationNanos);
        updateMax(dbDurationMaxNanos, durationNanos);
        dbActive.updateAndGet(current -> Math.max(current - 1, 0));
    }

    public void recordRedisCompensation() {
        redisCompensationCount.incrementAndGet();
    }

    public void recordReserveResult(ProductStockRedisReserveResult reserveResult) {
        if (reserveResult == null) {
            return;
        }

        switch (reserveResult) {
            case RESERVED -> redisReservedCount.incrementAndGet();
            case ALREADY_RESERVED -> redisAlreadyReservedCount.incrementAndGet();
            case OUT_OF_STOCK -> redisOutOfStockCount.incrementAndGet();
            case STOCK_KEY_MISSING -> redisStockKeyMissingCount.incrementAndGet();
            case INVALID_ARGUMENT -> redisInvalidArgumentCount.incrementAndGet();
            case DISABLED -> redisDisabledCount.incrementAndGet();
            case REDIS_UNAVAILABLE -> redisUnavailableCount.incrementAndGet();
        }
    }

    public ProductStockReservationPressureSnapshot snapshot() {
        return new ProductStockReservationPressureSnapshot(
                redisPreRejectedCount.get(),
                dbEntryCount.get(),
                dbSucceededCount.get(),
                dbRejectedCount.get(),
                dbFailedCount.get(),
                dbActiveMax.get(),
                averageDurationMillis(),
                maxDurationMillis(),
                redisCompensationCount.get(),
                redisReservedCount.get(),
                redisAlreadyReservedCount.get(),
                redisOutOfStockCount.get(),
                redisStockKeyMissingCount.get(),
                redisInvalidArgumentCount.get(),
                redisDisabledCount.get(),
                redisUnavailableCount.get()
        );
    }

    private double averageDurationMillis() {
        long entries = dbEntryCount.get();
        if (entries == 0) {
            return 0D;
        }

        return nanosToMillis(dbDurationTotalNanos.get()) / entries;
    }

    private double maxDurationMillis() {
        return nanosToMillis(dbDurationMaxNanos.get());
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000D;
    }

    private static void updateMax(AtomicInteger target, int candidate) {
        int current = target.get();
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get();
        }
    }

    private static void updateMax(AtomicLong target, long candidate) {
        long current = target.get();
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get();
        }
    }
}
