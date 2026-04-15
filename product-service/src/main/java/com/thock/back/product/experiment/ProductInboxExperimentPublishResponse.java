package com.thock.back.product.experiment;

public record ProductInboxExperimentPublishResponse(
        String runId,
        int publishedCount,
        long publishStartedAtMillis,
        long publishFinishedAtMillis
) {
}
