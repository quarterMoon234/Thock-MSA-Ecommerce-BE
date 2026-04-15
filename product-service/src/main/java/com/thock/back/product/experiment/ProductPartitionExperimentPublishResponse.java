package com.thock.back.product.experiment;

public record ProductPartitionExperimentPublishResponse(
        String runId,
        String topic,
        int publishedEventCount,
        int orderCount,
        int productCount,
        long publishStartedAtMillis,
        long publishFinishedAtMillis,
        long publishDurationMillis
) {
}
