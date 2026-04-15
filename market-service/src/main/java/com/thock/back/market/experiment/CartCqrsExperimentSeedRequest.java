package com.thock.back.market.experiment;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CartCqrsExperimentSeedRequest(
        Long baseMemberId,
        @NotEmpty List<Long> productIds,
        Integer readMemberCount,
        Integer addMemberCountPerScenario,
        Integer readItemQuantity,
        Integer addItemQuantity
) {
}
