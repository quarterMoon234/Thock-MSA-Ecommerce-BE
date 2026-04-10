package com.thock.back.product.experiment;

import com.thock.back.product.domain.Category;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProductStockExperimentCreateRequest(
        Long sellerId,
        String name,
        Long price,
        Long salePrice,
        Category category,
        @NotNull
        @PositiveOrZero
        Integer stock
) {
}
