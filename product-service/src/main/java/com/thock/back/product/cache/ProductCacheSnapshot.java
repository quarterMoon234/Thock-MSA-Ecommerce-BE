package com.thock.back.product.cache;

import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.dto.ProductDetailResponse;
import com.thock.back.product.in.dto.internal.ProductInternalResponse;

public record ProductCacheSnapshot (
        Long id,
        Long sellerId,
        String name,
        String imageUrl,
        Long price,
        Long salePrice,
        String description,
        Integer stock,
        Integer reservedStock,
        String category,
        String state
) {
    public static ProductCacheSnapshot from(Product product) {
        return new ProductCacheSnapshot(
                product.getId(),
                product.getSellerId(),
                product.getName(),
                product.getImageUrl(),
                product.getPrice(),
                product.getSalePrice(),
                product.getDescription(),
                product.getStock(),
                product.getReservedStock(),
                product.getCategory().name(),
                product.getState().name()
        );
    }

    public ProductDetailResponse toDetailResponse() {
        return new ProductDetailResponse(
                id,
                name,
                salePrice,
                description,
                stock,
                category
        );
    }

    public ProductInternalResponse toInternalResponse() {
        return new ProductInternalResponse(
                id,
                sellerId,
                name,
                imageUrl,
                price,
                salePrice,
                stock,
                reservedStock,
                state
        );
    }
}
