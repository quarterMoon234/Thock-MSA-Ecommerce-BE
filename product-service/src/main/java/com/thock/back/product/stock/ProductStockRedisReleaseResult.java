package com.thock.back.product.stock;

public enum ProductStockRedisReleaseResult {

    RELEASED(1L),
    RESERVATION_MISSING(2L),
    INVALID_ARGUMENT(-2L),
    DISABLED(null),
    REDIS_UNAVAILABLE(null);

    private final Long code;

    ProductStockRedisReleaseResult(Long code) {
        this.code = code;
    }

    public static ProductStockRedisReleaseResult fromCode(Long code) {
        if (code == null) {
            return REDIS_UNAVAILABLE;
        }

        for (ProductStockRedisReleaseResult result : values()) {
            if (result.code != null && result.code.equals(code)) {
                return result;
            }
        }

        return REDIS_UNAVAILABLE;
    }
}
