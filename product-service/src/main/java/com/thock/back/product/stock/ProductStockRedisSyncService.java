package com.thock.back.product.stock;

import com.thock.back.product.domain.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductStockRedisSyncService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductStockRedisProperties properties;
    private final ProductStockRedisKeyResolver keyResolver;

    public void sync(Product product) {
        if (!properties.isEnabled() || product == null || product.getId() == null) {
            return;
        }

        int availableStock = product.getStock() - product.getReservedStock();
        if (availableStock < 0) {
            log.warn(
                    "Product stock is inconsistent. productId={}, stock={}, reservedStock={}",
                    product.getId(),
                    product.getStock(),
                    product.getReservedStock()
            );
            availableStock = 0;
        }

        stringRedisTemplate.opsForValue()
                .set(keyResolver.availableKey(product.getId()), String.valueOf(availableStock));

        log.debug(
                "Synced product available stock to Redis. productId={}, availableStock={}",
                product.getId(),
                availableStock
        );
    }

    public void syncAfterCommit(Product product) {
        if (!properties.isEnabled() || product == null || product.getId() == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sync(product);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sync(product);
            }
        });
    }

    public void syncAll(List<Product> products) {
        if (!properties.isEnabled() || products == null || products.isEmpty()) {
            return;
        }

        for (Product product : products) {
            sync(product);
        }
    }

    public void evict(Long productId) {
        if (!properties.isEnabled() || productId == null) {
            return;
        }

        stringRedisTemplate.delete(keyResolver.availableKey(productId));

        log.debug("Evicted product available stock from Redis. productId={}", productId);
    }

    public void evictAfterCommit(Long productId) {
        if (!properties.isEnabled() || productId == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(productId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(productId);
            }
        });
    }

    public void evictAll(List<Long> productIds) {
        if (!properties.isEnabled() || productIds == null || productIds.isEmpty()) {
            return;
        }

        for (Long productId : productIds) {
            try {
                evict(productId);
            } catch (RuntimeException e) {
                log.warn("Failed to evict product available stock from Redis. productId={}", productId, e);
            }
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String keyPrefix() {
        return properties.getKeyPrefix();
    }

    public String availableKey(Long productId) {
        return keyResolver.availableKey(productId);
    }

    public boolean hasAvailableKey(Long productId) {
        if (productId == null) {
            return false;
        }

        Boolean exists = stringRedisTemplate.hasKey(keyResolver.availableKey(productId));
        return Boolean.TRUE.equals(exists);
    }

    public String findAvailableValue(Long productId) {
        if (productId == null) {
            return null;
        }

        return stringRedisTemplate.opsForValue().get(keyResolver.availableKey(productId));
    }
}
