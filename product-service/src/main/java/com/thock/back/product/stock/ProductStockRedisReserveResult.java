package com.thock.back.product.stock;

public enum ProductStockRedisReserveResult {

    RESERVED(1L),
    ALREADY_RESERVED(2L),
    OUT_OF_STOCK(0L),
    STOCK_KEY_MISSING(-1L),
    INVALID_ARGUMENT(-2L),
    DISABLED(null),
    REDIS_UNAVAILABLE(null);

    private final Long code;

    ProductStockRedisReserveResult(Long code) {
        this.code = code;
    }

    public static ProductStockRedisReserveResult fromCode(Long code) {
        if (code == null) {
            return REDIS_UNAVAILABLE;
        }

        for (ProductStockRedisReserveResult result : values()) {
            if (result.code != null && result.code.equals(code)) {
                return result;
            }
        }

        return REDIS_UNAVAILABLE;
    }

    public boolean shouldEnterDatabase() {
        return this == RESERVED
                || this == ALREADY_RESERVED
                || this == STOCK_KEY_MISSING
                || this == DISABLED
                || this == REDIS_UNAVAILABLE;
    }

    public boolean rejectedBeforeDatabase() {
        return this == OUT_OF_STOCK;
    }

    public boolean requiresRedisCompensation() {
        return this == RESERVED;
    }
}
