package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Profile("experiment")
public class ProductPartitionExperimentRecorder {

    private static final String ORDER_PREFIX = "partition-exp:";

    private final ConcurrentMap<String, RunState> runs = new ConcurrentHashMap<>();

    public void reset(ProductPartitionExperimentResetRequest request) {
        runs.put(
                request.runId(),
                new RunState(
                        request.runId(),
                        request.expectedOrderCount(),
                        request.expectedEventCount(),
                        request.startedAtMillis()
                )
        );
    }

    public void recordProcessed(
            String runId,
            String orderNumber,
            StockEventType eventType,
            int partition,
            String threadName,
            long processedAtMillis
    ) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            log.debug("Partition experiment run not found. runId={}", runId);
            return;
        }

        runState.recordProcessed(orderNumber, eventType, partition, threadName, processedAtMillis);
    }

    public void recordDuplicate(String runId) {
        RunState runState = runs.get(runId);
        if (runState != null) {
            runState.duplicateSkippedCount.incrementAndGet();
        }
    }

    public void recordFailure(String runId) {
        RunState runState = runs.get(runId);
        if (runState != null) {
            runState.failedCount.incrementAndGet();
        }
    }

    public ProductPartitionExperimentSummaryResponse getSummary(String runId) {
        RunState runState = runs.get(runId);
        if (runState == null) {
            return new ProductPartitionExperimentSummaryResponse(
                    runId,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0L,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    false
            );
        }

        return runState.toResponse();
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

    public static String createOrderNumber(String runId, int orderIndex) {
        return ORDER_PREFIX + runId + ":" + orderIndex;
    }

    private enum OrderStage {
        NONE,
        RESERVED,
        COMMITTED
    }

    private static final class OrderState {
        private OrderStage stage = OrderStage.NONE;

        synchronized boolean onProcessed(StockEventType eventType) {
            if (eventType == StockEventType.RESERVE) {
                if (stage == OrderStage.NONE) {
                    stage = OrderStage.RESERVED;
                    return false;
                }
                return true;
            }

            if (eventType == StockEventType.COMMIT) {
                if (stage == OrderStage.RESERVED) {
                    stage = OrderStage.COMMITTED;
                    return false;
                }
                return true;
            }

            return true;
        }

        synchronized boolean isCommitted() {
            return stage == OrderStage.COMMITTED;
        }
    }

    private static final class RunState {
        private final String runId;
        private final int expectedOrderCount;
        private final int expectedEventCount;
        private final long startedAtMillis;
        private final AtomicInteger processedEventCount = new AtomicInteger();
        private final AtomicInteger reserveProcessedCount = new AtomicInteger();
        private final AtomicInteger commitProcessedCount = new AtomicInteger();
        private final AtomicInteger duplicateSkippedCount = new AtomicInteger();
        private final AtomicInteger failedCount = new AtomicInteger();
        private final AtomicInteger orderingViolationCount = new AtomicInteger();
        private final AtomicInteger completedOrderCount = new AtomicInteger();
        private final AtomicLong firstProcessedAtMillis = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong lastProcessedAtMillis = new AtomicLong(0L);
        private final ConcurrentMap<Integer, AtomicInteger> partitionCounts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicInteger> threadCounts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, OrderState> orderStates = new ConcurrentHashMap<>();

        private RunState(
                String runId,
                int expectedOrderCount,
                int expectedEventCount,
                long startedAtMillis
        ) {
            this.runId = runId;
            this.expectedOrderCount = expectedOrderCount;
            this.expectedEventCount = expectedEventCount;
            this.startedAtMillis = startedAtMillis;
        }

        private void recordProcessed(
                String orderNumber,
                StockEventType eventType,
                int partition,
                String threadName,
                long processedAtMillis
        ) {
            processedEventCount.incrementAndGet();
            updateMin(firstProcessedAtMillis, processedAtMillis);
            updateMax(lastProcessedAtMillis, processedAtMillis);
            partitionCounts.computeIfAbsent(partition, ignored -> new AtomicInteger()).incrementAndGet();
            threadCounts.computeIfAbsent(threadName, ignored -> new AtomicInteger()).incrementAndGet();

            if (eventType == StockEventType.RESERVE) {
                reserveProcessedCount.incrementAndGet();
            } else if (eventType == StockEventType.COMMIT) {
                commitProcessedCount.incrementAndGet();
            }

            OrderState orderState = orderStates.computeIfAbsent(orderNumber, ignored -> new OrderState());
            boolean violation = orderState.onProcessed(eventType);
            if (violation) {
                orderingViolationCount.incrementAndGet();
                return;
            }

            if (eventType == StockEventType.COMMIT && orderState.isCommitted()) {
                completedOrderCount.incrementAndGet();
            }
        }

        private ProductPartitionExperimentSummaryResponse toResponse() {
            Long firstProcessed = firstProcessedAtMillis.get() == Long.MAX_VALUE
                    ? null
                    : firstProcessedAtMillis.get();
            Long lastProcessed = lastProcessedAtMillis.get() == 0L
                    ? null
                    : lastProcessedAtMillis.get();
            Long totalDuration = lastProcessed == null
                    ? null
                    : Math.max(lastProcessed - startedAtMillis, 0L);
            Double throughput = (totalDuration == null || totalDuration == 0L)
                    ? null
                    : processedEventCount.get() / (totalDuration / 1000D);

            return new ProductPartitionExperimentSummaryResponse(
                    runId,
                    expectedOrderCount,
                    expectedEventCount,
                    processedEventCount.get(),
                    reserveProcessedCount.get(),
                    commitProcessedCount.get(),
                    duplicateSkippedCount.get(),
                    failedCount.get(),
                    orderingViolationCount.get(),
                    completedOrderCount.get(),
                    startedAtMillis,
                    firstProcessed,
                    lastProcessed,
                    totalDuration,
                    throughput,
                    toPartitionCountMap(partitionCounts),
                    toThreadCountMap(threadCounts),
                    isCompleted()
            );
        }

        private boolean isCompleted() {
            return processedEventCount.get() >= expectedEventCount
                    && completedOrderCount.get() >= expectedOrderCount
                    && orderingViolationCount.get() == 0
                    && failedCount.get() == 0;
        }

        private static Map<Integer, Integer> toPartitionCountMap(ConcurrentMap<Integer, AtomicInteger> source) {
            Map<Integer, Integer> result = new LinkedHashMap<>();
            source.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue().get()));
            return result;
        }

        private static Map<String, Integer> toThreadCountMap(ConcurrentMap<String, AtomicInteger> source) {
            Map<String, Integer> result = new LinkedHashMap<>();
            source.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue().get()));
            return result;
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
