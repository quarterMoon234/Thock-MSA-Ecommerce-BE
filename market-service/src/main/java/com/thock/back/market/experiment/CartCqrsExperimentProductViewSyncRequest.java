package com.thock.back.market.experiment;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CartCqrsExperimentProductViewSyncRequest(
        @NotEmpty
        List<@NotNull Long> productIds
) {
}
