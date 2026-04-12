package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.cache.ProductCacheSnapshot;
import com.thock.back.product.cache.ProductCacheSyncService;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.messaging.inbox.ProductInboxGuard;
import com.thock.back.product.messaging.publisher.ProductEventPublisher;
import com.thock.back.product.monitoring.ProductStockReservationPressureMetrics;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.product.stock.ProductStockRedisCommitResult;
import com.thock.back.product.stock.ProductStockRedisReleaseResult;
import com.thock.back.product.stock.ProductStockRedisReservationService;
import com.thock.back.product.stock.ProductStockRedisSyncService;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.product.event.ProductEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStockTransactionalService {

    private final ProductRepository productRepository;
    private final ProductEventPublisher productEventPublisher;
    private final ProductCacheSyncService productCacheSyncService;
    private final ProductStockRedisReservationService productStockRedisReservationService;
    private final ProductStockRedisSyncService productStockRedisSyncService;
    private final ObjectProvider<ProductInboxGuard> inboxGuardProvider;
    private final ProductStockReservationPressureMetrics productStockReservationPressureMetrics;

    @Transactional
    public ProductStockTransactionResult handle(MarketOrderStockChangedEvent event) {
        handleInDatabase(event);
        return ProductStockTransactionResult.PROCESSED;
    }

    @Transactional
    public ProductStockTransactionResult handleKafka(
            MarketOrderStockChangedEvent event,
            String topic,
            String idempotencyKey,
            String consumerGroup
    ) {
        ProductInboxGuard inboxGuard = inboxGuardProvider.getIfAvailable();
        if (inboxGuard != null && !inboxGuard.tryClaim(idempotencyKey, topic, consumerGroup)) {
            return ProductStockTransactionResult.DUPLICATE_SKIPPED;
        }

        handleInDatabase(event);
        return ProductStockTransactionResult.PROCESSED;
    }

    private void handleInDatabase(MarketOrderStockChangedEvent event) {
        boolean reserveEvent = event.eventType() == StockEventType.RESERVE;
        long startedAtNanos = 0L;

        if (reserveEvent) {
            startedAtNanos = productStockReservationPressureMetrics.recordDbEntryStart();
        }

        try {
            doHandleInDatabase(event);

            if (reserveEvent) {
                productStockReservationPressureMetrics.recordDbSucceeded();
            }
        } catch (CustomException e) {
            if (reserveEvent && e.getErrorCode() == ErrorCode.PRODUCT_STOCK_NOT_ENOUGH) {
                productStockReservationPressureMetrics.recordDbRejected();
            } else if (reserveEvent) {
                productStockReservationPressureMetrics.recordDbFailed();
            }
            throw e;
        } catch (RuntimeException e) {
            if (reserveEvent) {
                productStockReservationPressureMetrics.recordDbFailed();
            }
            throw e;
        } finally {
            if (reserveEvent) {
                productStockReservationPressureMetrics.recordDbEntryFinish(startedAtNanos);
            }
        }
    }

    private void doHandleInDatabase(MarketOrderStockChangedEvent event) {
        List<Long> ids = event.items().stream()
                .map(StockOrderItemDto::productId)
                .distinct()
                .sorted()
                .toList();

        List<Product> products = productRepository.findAllByIdInForUpdate(ids);

        Map<Long, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (Long id : ids) {
            if (!productMap.containsKey(id)) {
                throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
            }
        }

        for (StockOrderItemDto item : event.items()) {
            Product product = productMap.get(item.productId());
            Integer qty = item.quantity();

            StockEventType type = event.eventType();
            if (type == StockEventType.RESERVE) {
                product.reserve(qty);
            } else if (type == StockEventType.RELEASE) {
                product.release(qty);
            } else if (type == StockEventType.COMMIT) {
                product.commit(qty);
            } else {
                throw new CustomException(ErrorCode.INVALID_REQUEST);
            }
        }

        registerRedisPostCommitIfNeeded(event, ids);

        productCacheSyncService.saveAllAfterCommit(
                products.stream()
                        .map(ProductCacheSnapshot::from)
                        .toList()
        );

        for (Product product : products) {
            productEventPublisher.publish(ProductEvent.builder()
                    .productId(product.getId())
                    .sellerId(product.getSellerId())
                    .name(product.getName())
                    .price(product.getPrice())
                    .salePrice(product.getSalePrice())
                    .description(product.getDescription())
                    .stock(product.getStock())
                    .reservedStock(product.getReservedStock())
                    .imageUrl(product.getImageUrl())
                    .productState(product.getState().name())
                    .eventType(ProductEventType.UPDATE)
                    .build());
        }
    }

    private void registerRedisPostCommitIfNeeded(MarketOrderStockChangedEvent event, List<Long> productIds) {
        if (event.eventType() != StockEventType.RELEASE && event.eventType() != StockEventType.COMMIT) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runRedisPostCommit(event, productIds);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runRedisPostCommit(event, productIds);
            }
        });
    }

    private void runRedisPostCommit(MarketOrderStockChangedEvent event, List<Long> productIds) {
        if (event.eventType() == StockEventType.RELEASE) {
            handleReleasePostCommit(event, productIds);
            return;
        }

        if (event.eventType() == StockEventType.COMMIT) {
            handleCommitPostCommit(event, productIds);
        }
    }

    private void handleReleasePostCommit(MarketOrderStockChangedEvent event, List<Long> productIds) {
        ProductStockRedisReleaseResult releaseResult = productStockRedisReservationService.release(
                event.orderNumber(),
                event.items()
        );

        if (releaseResult == ProductStockRedisReleaseResult.RELEASED
                || releaseResult == ProductStockRedisReleaseResult.DISABLED) {
            return;
        }

        productStockRedisSyncService.evictAll(productIds);

        log.warn(
                "Redis release post-commit fallback applied. orderNumber={}, result={}, productIds={}",
                event.orderNumber(),
                releaseResult,
                productIds
        );
    }

    private void handleCommitPostCommit(MarketOrderStockChangedEvent event, List<Long> productIds) {
        ProductStockRedisCommitResult commitResult = productStockRedisReservationService.commit(event.orderNumber());

        if (commitResult == ProductStockRedisCommitResult.COMMITTED
                || commitResult == ProductStockRedisCommitResult.RESERVATION_MISSING
                || commitResult == ProductStockRedisCommitResult.DISABLED) {
            return;
        }

        productStockRedisSyncService.evictAll(productIds);

        log.warn(
                "Redis commit post-commit fallback applied. orderNumber={}, result={}, productIds={}",
                event.orderNumber(),
                commitResult,
                productIds
        );
    }
}
