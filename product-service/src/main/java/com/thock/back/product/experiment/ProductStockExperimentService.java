package com.thock.back.product.experiment;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.app.ProductStockService;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.monitoring.ProductStockReservationPressureMetrics;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.product.stock.ProductStockRedisRebuildService;
import com.thock.back.product.stock.ProductStockRedisSyncService;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class ProductStockExperimentService {

    private static final Long DEFAULT_SELLER_ID = 1L;
    private static final Long DEFAULT_PRICE = 10_000L;
    private static final Category DEFAULT_CATEGORY = Category.KEYBOARD;

    private final ProductRepository productRepository;
    private final ProductStockService productStockService;
    private final ProductStockRedisSyncService productStockRedisSyncService;
    private final ProductStockRedisRebuildService productStockRedisRebuildService;
    private final ProductStockReservationPressureMetrics productStockReservationPressureMetrics;

    @Transactional
    public ProductStockExperimentProductResponse createProduct(ProductStockExperimentCreateRequest request) {
        Product product = Product.builder()
                .sellerId(request.sellerId() == null ? DEFAULT_SELLER_ID : request.sellerId())
                .category(request.category() == null ? DEFAULT_CATEGORY : request.category())
                .name(request.name() == null || request.name().isBlank() ? "stock-experiment-product" : request.name())
                .description("stock pessimistic lock experiment")
                .price(request.price() == null ? DEFAULT_PRICE : request.price())
                .salePrice(request.salePrice() == null ? request.price() : request.salePrice())
                .stock(request.stock())
                .imageUrl("https://example.com/stock-experiment.png")
                .detail(Map.of("source", "stock-experiment"))
                .build();

        Product saved = productRepository.saveAndFlush(product);
        productStockRedisSyncService.syncAfterCommit(saved);

        return ProductStockExperimentProductResponse.from(saved);
    }

    public ProductStockExperimentReservationResponse reserve(ProductStockExperimentReservationRequest request) {
        try {
            productStockService.handle(new MarketOrderStockChangedEvent(
                    request.orderNumber(),
                    StockEventType.RESERVE,
                    List.of(new StockOrderItemDto(request.productId(), request.quantity()))
            ));

            return ProductStockExperimentReservationResponse.reserved(request.orderNumber(), request.productId());
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.PRODUCT_STOCK_NOT_ENOUGH) {
                return ProductStockExperimentReservationResponse.rejected(
                        request.orderNumber(),
                        request.productId(),
                        e.getErrorCode().getCode()
                );
            }

            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ProductStockExperimentProductResponse getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        return ProductStockExperimentProductResponse.from(product);
    }

    public ProductStockExperimentProductResponse rebuildProductRedisStock(Long productId) {
        Product product = productStockRedisRebuildService.rebuild(productId);
        return ProductStockExperimentProductResponse.from(product);
    }

    public ProductStockExperimentRedisRebuildResponse rebuildAllRedisStock() {
        int rebuiltCount = productStockRedisRebuildService.rebuildAll();
        return new ProductStockExperimentRedisRebuildResponse(rebuiltCount);
    }

    public ProductStockExperimentRedisStateResponse getProductRedisState(Long productId) {
        return new ProductStockExperimentRedisStateResponse(
                productId,
                productStockRedisSyncService.isEnabled(),
                productStockRedisSyncService.keyPrefix(),
                productStockRedisSyncService.availableKey(productId),
                productStockRedisSyncService.hasAvailableKey(productId),
                productStockRedisSyncService.findAvailableValue(productId)
        );
    }

    public ProductStockExperimentMetricsResponse getMetrics() {
        return ProductStockExperimentMetricsResponse.from(productStockReservationPressureMetrics.snapshot());
    }

    public void resetMetrics() {
        productStockReservationPressureMetrics.reset();
    }
}
