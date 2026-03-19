package com.thock.back.market.domain;

import com.thock.back.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "market_cart_product_views")
public class CartProductView extends BaseIdAndTime {

    private Long productId;
    private Long sellerId;
    private String name;
    private String imageUrl;
    private Long price;
    private Long salePrice;
    private Integer stock;
    private String productState;
    private boolean deleted;

    public CartProductView(
            Long productId,
            Long sellerId,
            String name,
            String imageUrl,
            Long price,
            Long salePrice,
            Integer stock,
            String productState,
            boolean deleted
    ) {
        this.productId = productId;
        this.sellerId = sellerId;
        this.name = name;
        this.imageUrl = imageUrl;
        this.price = price;
        this.salePrice = salePrice;
        this.stock = stock;
        this.productState = productState;
        this.deleted = deleted;
    }

    public void sync(
            Long sellerId,
            String name,
            String imageUrl,
            Long price,
            Long salePrice,
            Integer stock,
            String productState,
            boolean deleted
    ) {
        this.sellerId = sellerId;
        this.name = name;
        this.imageUrl = imageUrl;
        this.price = price;
        this.salePrice = salePrice;
        this.stock = stock;
        this.productState = productState;
        this.deleted = deleted;
    }

    public int availableStock() {
        return stock == null ? 0 : Math.max(0, stock);
    }

    public boolean isAvailable() {
        return !deleted && "ON_SALE".equals(productState) && availableStock() > 0;
    }
}
