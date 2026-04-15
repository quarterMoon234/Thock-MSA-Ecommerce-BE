package com.thock.back.product.experiment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ProductPartitionExperimentPublishRequest(
        @NotBlank
        String runId,

        @NotBlank
        String topic,

        @NotEmpty
        List<Long> productIds,

        @NotNull
        @Min(2)
        Integer totalEventCount,

        @NotNull
        @Min(1)
        Integer quantity
) {
}
