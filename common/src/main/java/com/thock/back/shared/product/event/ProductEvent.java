package com.thock.back.shared.product.event;

import lombok.Builder;

@Builder
public record ProductEvent(
        Long productId,
        Long sellerId,
        String name,
        Long price,
        Long salePrice,
        String description,
        Integer stock,
        Integer reservedStock,
        String imageUrl,
        String productState,
        ProductEventType eventType  // "CREATE", "UPDATE", "DELETE"
) {}
