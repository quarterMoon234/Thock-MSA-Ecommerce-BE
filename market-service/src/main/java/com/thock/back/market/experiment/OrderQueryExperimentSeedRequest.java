package com.thock.back.market.experiment;

public record OrderQueryExperimentSeedRequest(
        Long memberId,
        Integer orderCount,
        Integer itemsPerOrder
) {
}
