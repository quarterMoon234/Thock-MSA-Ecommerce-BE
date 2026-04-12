package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.monitoring.ProductStockReservationPressureMetrics;
import com.thock.back.product.stock.ProductStockRedisReservationService;
import com.thock.back.product.stock.ProductStockRedisReserveResult;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductStockRedisReservationService productStockRedisReservationService;
    private final ProductStockTransactionalService productStockTransactionalService;
    private final ProductStockReservationPressureMetrics productStockReservationPressureMetrics;

    public void handle(MarketOrderStockChangedEvent event) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            return;
        }

        ProductStockRedisReserveResult redisReserveResult = reserveInRedisIfNeeded(event);

        try {
            productStockTransactionalService.handle(event);
        } catch (RuntimeException e) {
            compensateRedisReservationIfNeeded(event, redisReserveResult);
            throw e;
        }
    }

    public boolean handleKafka(
            MarketOrderStockChangedEvent event,
            String topic,
            String idempotencyKey,
            String consumerGroup
    ) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            return true;
        }

        ProductStockRedisReserveResult redisReserveResult = reserveInRedisIfNeeded(event);

        try {
            ProductStockTransactionResult transactionResult = productStockTransactionalService.handleKafka(
                    event,
                    topic,
                    idempotencyKey,
                    consumerGroup
            );

            if (transactionResult == ProductStockTransactionResult.DUPLICATE_SKIPPED) {
                compensateRedisReservationIfNeeded(event, redisReserveResult);
                return false;
            }

            return true;
        } catch (RuntimeException e) {
            compensateRedisReservationIfNeeded(event, redisReserveResult);
            throw e;
        }
    }

    private ProductStockRedisReserveResult reserveInRedisIfNeeded(MarketOrderStockChangedEvent event) {
        if (event.eventType() != StockEventType.RESERVE) {
            return ProductStockRedisReserveResult.DISABLED;
        }

        ProductStockRedisReserveResult reserveResult = productStockRedisReservationService.tryReserve(
                event.orderNumber(),
                event.items()
        );
        productStockReservationPressureMetrics.recordReserveResult(reserveResult);

        if (reserveResult.rejectedBeforeDatabase()) {
            productStockReservationPressureMetrics.recordRedisPreRejected();
            throw new CustomException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        if (!reserveResult.shouldEnterDatabase()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        return reserveResult;
    }

    private void compensateRedisReservationIfNeeded(
            MarketOrderStockChangedEvent event,
            ProductStockRedisReserveResult reserveResult
    ) {
        if (event.eventType() != StockEventType.RESERVE || !reserveResult.requiresRedisCompensation()) {
            return;
        }

        productStockReservationPressureMetrics.recordRedisCompensation();
        productStockRedisReservationService.release(
                event.orderNumber(),
                event.items()
        );
    }
}
