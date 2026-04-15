package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductInboxExperimentResetRequest(
        @NotBlank
        String runId,

        @NotNull
        Long productId,

        @NotBlank
        String orderNumber,

        @NotNull
        StockEventType eventType,

        @NotNull
        @Min(1)
        Integer quantity,

        @NotNull
        @Min(1)
        Integer expectedMessageCount,

        @NotBlank
        String topic,

        @NotBlank
        String consumerGroup,

        @NotNull
        Integer initialStock,

        @NotNull
        Integer initialReservedStock,

        @NotNull
        Long startedAtMillis
) {
}
