package com.thock.back.product.stock;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "product.stock.redis")
public class ProductStockRedisProperties {

    private boolean enabled = false;
    private String keyPrefix = "product:stock";
    private long reservationTtlSeconds = 1800;

}
