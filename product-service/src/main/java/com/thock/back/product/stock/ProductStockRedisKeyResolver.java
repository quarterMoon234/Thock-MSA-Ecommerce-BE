package com.thock.back.product.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductStockRedisKeyResolver {

    private final ProductStockRedisProperties properties;

    public String availableKey(Long productId) {
        return properties.getKeyPrefix() + ":available:" + productId;
    }

    public String reservationKey(String orderNumber) {
        return properties.getKeyPrefix() + ":reservation:" + orderNumber;
    }
}
