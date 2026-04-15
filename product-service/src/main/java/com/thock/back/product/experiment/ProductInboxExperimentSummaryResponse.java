package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;

public record ProductInboxExperimentSummaryResponse(
        String runId,
        Long productId,
        String orderNumber,
        StockEventType eventType,
        int quantity,
        int expectedMessageCount,
        int processedCount,
        int duplicateSkippedCount,
        int failedCount,
        long startedAtMillis,
        Long firstHandledAtMillis,
        Long lastHandledAtMillis,
        Long totalDurationMillis,
        Integer initialStock,
        Integer initialReservedStock,
        Integer initialAvailableStock,
        Integer finalStock,
        Integer finalReservedStock,
        Integer finalAvailableStock,
        Integer reservedDelta,
        Integer availableDelta,
        Integer appliedReservationCount,
        long inboxRecordCount,
        boolean completed
) {
}
