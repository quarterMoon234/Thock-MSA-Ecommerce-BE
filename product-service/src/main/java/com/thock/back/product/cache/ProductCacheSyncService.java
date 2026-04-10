package com.thock.back.product.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProductCacheSyncService {

    private final ProductCacheStore productCacheStore;

    public void saveAfterCommit(ProductCacheSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            productCacheStore.save(snapshot);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productCacheStore.save(snapshot);
            }
        });
    }

    public void saveAllAfterCommit(List<ProductCacheSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            productCacheStore.saveAll(snapshots);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productCacheStore.saveAll(snapshots);
            }
        });
    }

    public void evictAfterCommit(Long productId) {
        if (productId == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            productCacheStore.evict(productId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productCacheStore.evict(productId);
            }
        });
    }
}
