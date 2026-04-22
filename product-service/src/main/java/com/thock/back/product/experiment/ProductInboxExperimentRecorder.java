package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Profile("experiment")
public class ProductInboxExperimentRecorder {

    private static final String ORDER_PREFIX = "inbox-exp:";

    private final ConcurrentMap<String, RunState> runs = new ConcurrentHashMap<>();

    public void reset(ProductInboxExperimentResetRequest request) {
        runs.put(
                request.runId(),
                new RunState(
                        request.runId(),
                        request.productId(),
                        request.orderNumber(),
                        request.eventType(),
                        request.quantity(),
                        request.expectedMessageCount(),
                        request.topic(),
                        request.consumerGroup(),
                        request.initialStock(),
                        request.initialReservedStock(),
                        request.startedAtMillis()
                )
        );
    }

    public void recordProcessed(String runId, long handledAtMillis) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            log.debug("Inbox experiment run not found. runId={}", runId);
            return;
        }

        runState.recordProcessed(handledAtMillis);
    }

    public void recordDuplicate(String runId, long handledAtMillis) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            log.debug("Inbox experiment run not found for duplicate. runId={}", runId);
            return;
        }

        runState.recordDuplicate(handledAtMillis);
    }

    public void recordFailure(String runId, long handledAtMillis) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            log.debug("Inbox experiment run not found for failure. runId={}", runId);
            return;
        }

        runState.recordFailure(handledAtMillis);
    }

    public RunSnapshot getSnapshot(String runId) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            return RunSnapshot.empty(runId);
        }

        return runState.toSnapshot();
    }

    public static boolean isExperimentOrderNumber(String orderNumber) {
        return orderNumber != null && orderNumber.startsWith(ORDER_PREFIX);
    }

    public static String extractRunId(String orderNumber) {
        if (!isExperimentOrderNumber(orderNumber)) {
            return null;
        }

        String[] parts = orderNumber.split(":");
        if (parts.length < 3) {
            return null;
        }

        return parts[1];
    }

    public static String createOrderNumber(String runId) {
        return ORDER_PREFIX + runId + ":order-1";
    }

    public record RunSnapshot(
            String runId,
            Long productId,
            String orderNumber,
            StockEventType eventType,
            int quantity,
            int expectedMessageCount,
            String topic,
            String consumerGroup,
            int processedCount,
            int duplicateSkippedCount,
            int failedCount,
            long startedAtMillis,
            Long firstHandledAtMillis,
            Long lastHandledAtMillis,
            Long totalDurationMillis,
            int initialStock,
            int initialReservedStock,
            boolean completed
    ) {
        static RunSnapshot empty(String runId) {
            return new RunSnapshot(
                    runId,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0L,
                    null,
                    null,
                    null,
                    0,
                    0,
                    false
            );
        }
    }

    private static final class RunState {
        private final String runId;
        private final Long productId;
        private final String orderNumber;
        private final StockEventType eventType;
        private final int quantity;
        private final int expectedMessageCount;
        private final String topic;
        private final String consumerGroup;
        private final int initialStock;
        private final int initialReservedStock;
        private final long startedAtMillis;
        private final AtomicInteger processedCount = new AtomicInteger();
        private final AtomicInteger duplicateSkippedCount = new AtomicInteger();
        private final AtomicInteger failedCount = new AtomicInteger();
        private final AtomicLong firstHandledAtMillis = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong lastHandledAtMillis = new AtomicLong(0L);

        private RunState(
                String runId,
                Long productId,
                String orderNumber,
                StockEventType eventType,
                int quantity,
                int expectedMessageCount,
                String topic,
                String consumerGroup,
                int initialStock,
                int initialReservedStock,
                long startedAtMillis
        ) {
            this.runId = runId;
            this.productId = productId;
            this.orderNumber = orderNumber;
            this.eventType = eventType;
            this.quantity = quantity;
            this.expectedMessageCount = expectedMessageCount;
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.initialStock = initialStock;
            this.initialReservedStock = initialReservedStock;
            this.startedAtMillis = startedAtMillis;
        }

        private void recordProcessed(long handledAtMillis) {
            processedCount.incrementAndGet();
            updateHandledAt(handledAtMillis);
        }

        private void recordDuplicate(long handledAtMillis) {
            duplicateSkippedCount.incrementAndGet();
            updateHandledAt(handledAtMillis);
        }

        private void recordFailure(long handledAtMillis) {
            failedCount.incrementAndGet();
            updateHandledAt(handledAtMillis);
        }

        private void updateHandledAt(long handledAtMillis) {
            updateMin(firstHandledAtMillis, handledAtMillis);
            updateMax(lastHandledAtMillis, handledAtMillis);
        }

        private RunSnapshot toSnapshot() {
            Long firstHandled = firstHandledAtMillis.get() == Long.MAX_VALUE
                    ? null
                    : firstHandledAtMillis.get();
            Long lastHandled = lastHandledAtMillis.get() == 0L
                    ? null
                    : lastHandledAtMillis.get();
            Long totalDuration = lastHandled == null
                    ? null
                    : Math.max(lastHandled - startedAtMillis, 0L);

            return new RunSnapshot(
                    runId,
                    productId,
                    orderNumber,
                    eventType,
                    quantity,
                    expectedMessageCount,
                    topic,
                    consumerGroup,
                    processedCount.get(),
                    duplicateSkippedCount.get(),
                    failedCount.get(),
                    startedAtMillis,
                    firstHandled,
                    lastHandled,
                    totalDuration,
                    initialStock,
                    initialReservedStock,
                    isCompleted()
            );
        }

        private boolean isCompleted() {
            return processedCount.get() + duplicateSkippedCount.get() + failedCount.get() >= expectedMessageCount;
        }

        private static void updateMin(AtomicLong target, long candidate) {
            long current = target.get();
            while (candidate < current && !target.compareAndSet(current, candidate)) {
                current = target.get();
            }
        }

        private static void updateMax(AtomicLong target, long candidate) {
            long current = target.get();
            while (candidate > current && !target.compareAndSet(current, candidate)) {
                current = target.get();
            }
        }
    }
}
