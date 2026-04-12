package com.thock.back.product.experiment;

import com.thock.back.product.monitoring.ProductStockReservationPressureSnapshot;

public record ProductStockExperimentMetricsResponse(
        long redisPreRejectedCount,
        long dbEntryCount,
        long dbSucceededCount,
        long dbRejectedCount,
        long dbFailedCount,
        long dbActiveMax,
        double dbDurationAvgMs,
        double dbDurationMaxMs,
        long redisCompensationCount,
        long redisReservedCount,
        long redisAlreadyReservedCount,
        long redisOutOfStockCount,
        long redisStockKeyMissingCount,
        long redisInvalidArgumentCount,
        long redisDisabledCount,
        long redisUnavailableCount
) {

    public static ProductStockExperimentMetricsResponse from(ProductStockReservationPressureSnapshot snapshot) {
        return new ProductStockExperimentMetricsResponse(
                snapshot.redisPreRejectedCount(),
                snapshot.dbEntryCount(),
                snapshot.dbSucceededCount(),
                snapshot.dbRejectedCount(),
                snapshot.dbFailedCount(),
                snapshot.dbActiveMax(),
                snapshot.dbDurationAvgMs(),
                snapshot.dbDurationMaxMs(),
                snapshot.redisCompensationCount(),
                snapshot.redisReservedCount(),
                snapshot.redisAlreadyReservedCount(),
                snapshot.redisOutOfStockCount(),
                snapshot.redisStockKeyMissingCount(),
                snapshot.redisInvalidArgumentCount(),
                snapshot.redisDisabledCount(),
                snapshot.redisUnavailableCount()
        );
    }
}
