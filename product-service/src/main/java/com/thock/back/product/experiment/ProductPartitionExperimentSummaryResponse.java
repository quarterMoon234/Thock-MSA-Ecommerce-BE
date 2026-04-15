package com.thock.back.product.experiment;

import java.util.Map;

public record ProductPartitionExperimentSummaryResponse(
        String runId,
        int expectedOrderCount,
        int expectedEventCount,
        int processedEventCount,
        int reserveProcessedCount,
        int commitProcessedCount,
        int duplicateSkippedCount,
        int failedCount,
        int orderingViolationCount,
        int completedOrderCount,
        long startedAtMillis,
        Long firstProcessedAtMillis,
        Long lastProcessedAtMillis,
        Long totalDurationMillis,
        Double throughputEventsPerSecond,
        Map<Integer, Integer> partitionCounts,
        Map<String, Integer> threadCounts,
        boolean completed
) {
}
