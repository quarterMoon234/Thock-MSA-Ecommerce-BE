package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.cache.ProductCacheSnapshot;
import com.thock.back.product.cache.ProductCacheStore;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.dto.ProductDetailResponse;
import com.thock.back.product.in.dto.ProductSearchResponse;
import com.thock.back.product.in.dto.ProductSearchRequest;
import com.thock.back.product.in.dto.internal.ProductInternalResponse;
import com.thock.back.product.monitoring.ProductCacheMetrics;
import com.thock.back.product.out.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ProductQueryService {
    private final ProductRepository productRepository;
    private final ProductCacheStore productCacheStore;
    private final ProductCacheMetrics productCacheMetrics;

    public Page<ProductSearchResponse> searchProductsByCategory(Category category, Pageable pageable) {
        return productRepository.findByCategory(category, pageable)
                .map(ProductSearchResponse::new);
    }

    public ProductDetailResponse getProductById(Long id) {
        return productCacheStore.findById(id)
                .map(snapshot -> {
                    log.info("Product detail cache hit. productId={}", id);
                    productCacheMetrics.recordDetailHit();
                    return snapshot.toDetailResponse();
                })
                .orElseGet(() -> {
                    log.info("Product detail cache miss. productId={}", id);
                    productCacheMetrics.recordDetailMiss();

                    Product product = productRepository.findById(id)
                            .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

                    ProductCacheSnapshot snapshot = ProductCacheSnapshot.from(product);
                    productCacheStore.save(snapshot);

                    return snapshot.toDetailResponse();
                });
    }

    public List<ProductInternalResponse> getProductsByIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductCacheSnapshot> cachedSnapshots = productCacheStore.findAllByIds(productIds);

        List<Long> missedProductIds = productIds.stream()
                .filter(productId -> !cachedSnapshots.containsKey(productId))
                .distinct()
                .toList();

        int hitCount = cachedSnapshots.size();
        int missCount = missedProductIds.size();

        if (!missedProductIds.isEmpty()) {
            log.info("Product internal list cache miss. requestedCount={}, hitCount={}, missCount={}",
                    productIds.size(),
                    hitCount,
                    missCount);

            productCacheMetrics.recordInternalHit(hitCount);
            productCacheMetrics.recordInternalMiss(missCount);

            List<ProductCacheSnapshot> missedSnapshots = productRepository.findAllByIdIn(missedProductIds).stream()
                    .map(ProductCacheSnapshot::from)
                    .toList();

            productCacheStore.saveAll(missedSnapshots);

            for (ProductCacheSnapshot snapshot : missedSnapshots) {
                cachedSnapshots.put(snapshot.id(), snapshot);
            }
        } else {
            log.info("Product internal list cache hit. requestedCount={}, hitCount={}, missCount=0",
                    productIds.size(),
                    hitCount);

            productCacheMetrics.recordInternalHit(hitCount);
        }

        return productIds.stream()
                .map(cachedSnapshots::get)
                .filter(Objects::nonNull)
                .map(ProductCacheSnapshot::toInternalResponse)
                .toList();
    }

    public Page<ProductSearchResponse> searchProducts(ProductSearchRequest condition, Pageable pageable) {
        return productRepository.search(condition, pageable);
    }

    public Page<ProductSearchResponse> getMyProducts(Long sellerId, Pageable pageable) {
        return productRepository.findBySellerId(sellerId, pageable)
                .map(ProductSearchResponse::new);
    }
}
