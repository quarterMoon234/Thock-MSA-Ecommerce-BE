package com.thock.back.product.experiment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProductStockExperimentReservationRequest(
        @NotBlank
        String orderNumber,
        @NotNull
        @Positive
        Long productId,
        @NotNull
        @Positive
        Integer quantity
) {
}
