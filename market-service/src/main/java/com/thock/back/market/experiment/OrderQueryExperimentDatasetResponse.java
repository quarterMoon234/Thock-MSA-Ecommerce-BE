package com.thock.back.market.experiment;

public record OrderQueryExperimentDatasetResponse(
        Long memberId,
        int orderCount,
        int itemCount
) {
}
