package com.thock.back.product.app;

import com.thock.back.product.cache.ProductCacheSnapshot;
import com.thock.back.product.cache.ProductCacheSyncService;
import com.thock.back.product.domain.command.ProductCreateCommand;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.domain.service.ProductAuthorizationValidator;
import com.thock.back.product.messaging.publisher.ProductEventPublisher;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.product.stock.ProductStockRedisSyncService;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.product.event.ProductEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductCreateService {
    private final ProductRepository productRepository;
    private final ProductEventPublisher productEventPublisher;
    private final ProductCacheSyncService productCacheSyncService;
    private final ProductAuthorizationValidator authorizationValidator;
    private final ProductStockRedisSyncService productStockRedisSyncService;

    public Long createProduct(ProductCreateCommand command) {

        authorizationValidator.validateSellerRole(command.role());

        Product product = Product.builder()
                .sellerId(command.sellerId())
                .name(command.name())
                .price(command.price())
                .salePrice(command.salePrice())
                .stock(command.stock())
                .category(command.category())
                .description(command.description())
                .imageUrl(command.imageUrl())
                .detail(command.detail())
                .build();

        // DB 저장
        Product saved = productRepository.save(product);

        // Lua 용 동기화
        productStockRedisSyncService.syncAfterCommit(saved);

        // 캐시 저장
        productCacheSyncService.saveAfterCommit(ProductCacheSnapshot.from(saved));

        // 상품 동기화 이벤트 발행
        productEventPublisher.publish(ProductEvent.builder()
                .productId(saved.getId())
                .sellerId(saved.getSellerId())
                .name(saved.getName())
                .price(saved.getPrice())
                .salePrice(saved.getSalePrice())
                .description(saved.getDescription())
                .stock(saved.getStock())
                .reservedStock(saved.getReservedStock())
                .imageUrl(saved.getImageUrl())
                .productState(saved.getState().name())
                .eventType(ProductEventType.CREATE)
                .build());

        return saved.getId();
    }
}
