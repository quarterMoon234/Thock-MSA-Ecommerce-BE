package com.thock.back.product.experiment;

public record ProductStockExperimentRedisStateResponse(
        Long productId,
        boolean redisEnabled,
        String keyPrefix,
        String availableKey,
        boolean availableKeyExists,
        String availableValue
) {
}
