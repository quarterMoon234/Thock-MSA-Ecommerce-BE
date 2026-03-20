package com.thock.back.market.domain;

import com.thock.back.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "market_cart_product_views")
@NoArgsConstructor
@Getter
public class CartProductView extends BaseIdAndTime {

    @Column(nullable = false, unique = true)
    private Long productId;
    private Long sellerId;
    private String name;
    private String imageUrl;
    private Long price;
    private Long salePrice;
    private Integer stock;
    private Integer reservedStock;
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
            Integer reservedStock,
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
        this.reservedStock = reservedStock;
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
            Integer reservedStock,
            String productState,
            boolean deleted
    ) {
        this.sellerId = sellerId;
        this.name = name;
        this.imageUrl = imageUrl;
        this.price = price;
        this.salePrice = salePrice;
        this.stock = stock;
        this.reservedStock = reservedStock;
        this.productState = productState;
        this.deleted = deleted;
    }

    public int availableStock() {
        int totalStock = stock == null ? 0 : stock;
        int reserved = reservedStock == null ? 0 : reservedStock;
        return Math.max(0, totalStock - reserved);
    }

    public boolean isAvailable() {
        return !deleted && "ON_SALE".equals(productState) && availableStock() > 0;
    }
}
