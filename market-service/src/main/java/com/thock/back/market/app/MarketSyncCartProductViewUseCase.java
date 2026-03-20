package com.thock.back.market.app;

import com.thock.back.market.domain.CartProductView;
import com.thock.back.market.domain.CartProductViewRepository;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.product.event.ProductEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketSyncCartProductViewUseCase {

    private final CartProductViewRepository cartProductViewRepository;

    @Transactional
    public void sync(ProductEvent event) {
        CartProductView view = cartProductViewRepository.findByProductId(event.productId())
                .orElseGet(() -> new CartProductView(
                        event.productId(),
                        event.sellerId(),
                        event.name(),
                        event.imageUrl(),
                        event.price(),
                        event.salePrice(),
                        stock(event),
                        reservedStock(event),
                        productState(event),
                        isDeleted(event)
                ));

        view.sync(
                event.sellerId(),
                event.name(),
                event.imageUrl(),
                event.price(),
                event.salePrice(),
                stock(event),
                reservedStock(event),
                productState(event),
                isDeleted(event)
        );

        cartProductViewRepository.save(view);
    }

    private int stock(ProductEvent event) {
        return event.eventType() == ProductEventType.DELETE ? 0 : nullToZero(event.stock());
    }

    private int reservedStock(ProductEvent event) {
        return event.eventType() == ProductEventType.DELETE ? 0 : nullToZero(event.reservedStock());
    }

    private String productState(ProductEvent event) {
        return event.productState();
    }

    private boolean isDeleted(ProductEvent event) {
        return event.eventType() == ProductEventType.DELETE;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
