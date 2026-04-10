package com.thock.back.product.experiment;

import com.thock.back.product.domain.entity.Product;

public record ProductStockExperimentProductResponse(
        Long productId,
        Integer stock,
        Integer reservedStock,
        Integer availableStock
) {
    public static ProductStockExperimentProductResponse from(Product product) {
        return new ProductStockExperimentProductResponse(
                product.getId(),
                product.getStock(),
                product.getReservedStock(),
                product.getStock() - product.getReservedStock()
        );
    }
}
