package com.thock.back.product.monitoring;

public record ProductStockReservationPressureSnapshot(
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
}
