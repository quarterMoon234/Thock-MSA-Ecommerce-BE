package com.thock.back.product.stock;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.out.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductStockRedisRebuildService {

    private final ProductRepository productRepository;
    private final ProductStockRedisSyncService productStockRedisSyncService;

    @Transactional(readOnly = true)
    public Product rebuild(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        productStockRedisSyncService.sync(product);
        return product;
    }

    @Transactional(readOnly = true)
    public int rebuildAll() {
        List<Product> products = productRepository.findAll();
        productStockRedisSyncService.syncAll(products);
        return products.size();
    }
}
