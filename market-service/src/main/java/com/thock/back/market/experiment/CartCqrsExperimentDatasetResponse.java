package com.thock.back.market.experiment;

import java.util.List;

public record CartCqrsExperimentDatasetResponse(
        Long baseMemberId,
        List<Long> productIds,
        Long addTargetProductId,
        List<Long> readMemberIds,
        List<Long> syncAddMemberIds,
        List<Long> cqrsAddMemberIds,
        List<Long> syncAddDelayMemberIds,
        List<Long> cqrsAddDelayMemberIds
) {
}
