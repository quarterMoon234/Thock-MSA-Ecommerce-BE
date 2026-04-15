package com.thock.back.product.experiment;

import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class ProductPartitionExperimentService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ProductPartitionExperimentRecorder productPartitionExperimentRecorder;

    public void reset(@Valid ProductPartitionExperimentResetRequest request) {
        productPartitionExperimentRecorder.reset(request);
    }

    public ProductPartitionExperimentPublishResponse publish(@Valid ProductPartitionExperimentPublishRequest request) {
        if (request.totalEventCount() % 2 != 0) {
            throw new IllegalArgumentException("totalEventCount must be even.");
        }

        int orderCount = request.totalEventCount() / 2;
        long publishStartedAtMillis = System.currentTimeMillis();
        List<CompletableFuture<?>> futures = new ArrayList<>(request.totalEventCount());

        for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
            Long productId = request.productIds().get(orderIndex % request.productIds().size());
            String orderNumber = ProductPartitionExperimentRecorder.createOrderNumber(request.runId(), orderIndex);
            List<StockOrderItemDto> items = List.of(new StockOrderItemDto(productId, request.quantity()));

            futures.add(kafkaTemplate.send(
                    request.topic(),
                    orderNumber,
                    new MarketOrderStockChangedEvent(orderNumber, StockEventType.RESERVE, items)
            ));
            futures.add(kafkaTemplate.send(
                    request.topic(),
                    orderNumber,
                    new MarketOrderStockChangedEvent(orderNumber, StockEventType.COMMIT, items)
            ));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        kafkaTemplate.flush();

        long publishFinishedAtMillis = System.currentTimeMillis();

        return new ProductPartitionExperimentPublishResponse(
                request.runId(),
                request.topic(),
                request.totalEventCount(),
                orderCount,
                request.productIds().size(),
                publishStartedAtMillis,
                publishFinishedAtMillis,
                Math.max(publishFinishedAtMillis - publishStartedAtMillis, 0L)
        );
    }

    public ProductPartitionExperimentSummaryResponse getSummary(String runId) {
        return productPartitionExperimentRecorder.getSummary(runId);
    }
}
