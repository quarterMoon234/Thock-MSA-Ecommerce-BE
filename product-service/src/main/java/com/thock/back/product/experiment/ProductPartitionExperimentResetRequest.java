package com.thock.back.product.experiment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductPartitionExperimentResetRequest(
        @NotBlank
        String runId,

        @NotNull
        @Min(1)
        Integer expectedOrderCount,

        @NotNull
        @Min(1)
        Integer expectedEventCount,

        @NotNull
        Long startedAtMillis
) {
}
