package com.thock.back.product.experiment;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.idempotency.ProductInboundEventIdempotencyKeyResolver;
import com.thock.back.product.messaging.inbox.ProductInboxEventRepository;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class ProductInboxExperimentService {

    private final ProductInboxExperimentRecorder recorder;
    private final ProductRepository productRepository;
    private final ProductInboxEventRepository productInboxEventRepository;
    private final ProductInboundEventIdempotencyKeyResolver idempotencyKeyResolver;
    private final @Qualifier("productKafkaTemplate") KafkaTemplate<String, Object> productKafkaTemplate;

    public void reset(ProductInboxExperimentResetRequest request) {
        recorder.reset(request);
    }

    public ProductInboxExperimentPublishResponse publish(ProductInboxExperimentPublishRequest request) {
        long publishStartedAtMillis = System.currentTimeMillis();
        StockOrderItemDto item = new StockOrderItemDto(request.productId(), request.quantity());
        MarketOrderStockChangedEvent event = new MarketOrderStockChangedEvent(
                request.orderNumber(),
                request.eventType(),
                List.of(item)
        );

        CompletableFuture<?>[] futures = new CompletableFuture<?>[request.duplicateCount()];
        for (int index = 0; index < request.duplicateCount(); index += 1) {
            futures[index] = productKafkaTemplate.send(request.topic(), request.orderNumber(), event);
        }

        CompletableFuture.allOf(futures).join();
        long publishFinishedAtMillis = System.currentTimeMillis();

        return new ProductInboxExperimentPublishResponse(
                request.runId(),
                request.duplicateCount(),
                publishStartedAtMillis,
                publishFinishedAtMillis
        );
    }

    public ProductInboxExperimentSummaryResponse getSummary(String runId) {
        ProductInboxExperimentRecorder.RunSnapshot snapshot = recorder.getSnapshot(runId);

        if (snapshot.productId() == null) {
            return new ProductInboxExperimentSummaryResponse(
                    runId,
                    null,
                    null,
                    null,
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
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    false
            );
        }

        Product product = productRepository.findById(snapshot.productId())
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        MarketOrderStockChangedEvent event = new MarketOrderStockChangedEvent(
                snapshot.orderNumber(),
                snapshot.eventType(),
                List.of(new StockOrderItemDto(snapshot.productId(), snapshot.quantity()))
        );
        String idempotencyKey = idempotencyKeyResolver.stockChanged(event);

        long inboxRecordCount = productInboxEventRepository.countByTopicAndIdempotencyKey(snapshot.topic(), idempotencyKey);

        int finalAvailableStock = product.getStock() - product.getReservedStock();
        int initialAvailableStock = snapshot.initialStock() - snapshot.initialReservedStock();
        int reservedDelta = product.getReservedStock() - snapshot.initialReservedStock();
        int availableDelta = initialAvailableStock - finalAvailableStock;
        int appliedReservationCount = snapshot.quantity() <= 0 ? 0 : reservedDelta / snapshot.quantity();

        return new ProductInboxExperimentSummaryResponse(
                snapshot.runId(),
                snapshot.productId(),
                snapshot.orderNumber(),
                snapshot.eventType(),
                snapshot.quantity(),
                snapshot.expectedMessageCount(),
                snapshot.processedCount(),
                snapshot.duplicateSkippedCount(),
                snapshot.failedCount(),
                snapshot.startedAtMillis(),
                snapshot.firstHandledAtMillis(),
                snapshot.lastHandledAtMillis(),
                snapshot.totalDurationMillis(),
                snapshot.initialStock(),
                snapshot.initialReservedStock(),
                initialAvailableStock,
                product.getStock(),
                product.getReservedStock(),
                finalAvailableStock,
                reservedDelta,
                availableDelta,
                appliedReservationCount,
                inboxRecordCount,
                snapshot.completed()
        );
    }
}
