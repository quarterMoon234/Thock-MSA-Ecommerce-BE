package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductInboxExperimentPublishRequest(
        @NotBlank
        String runId,

        @NotBlank
        String topic,

        @NotBlank
        String orderNumber,

        @NotNull
        StockEventType eventType,

        @NotNull
        Long productId,

        @NotNull
        @Min(1)
        Integer quantity,

        @NotNull
        @Min(1)
        Integer duplicateCount
) {
}
