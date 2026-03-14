package com.thock.back.market.out.api.dto;

import lombok.Value;

@Value
public class ProductInfo {
    Long id;
    Long sellerId;
    String name;
    String imageUrl;
    Long price;
    Long salePrice;
    Integer stock;
    Integer reservedStock;
    String state; // ProductState

    public boolean isAvailable() {
        return "ON_SALE".equals(state) && availableStock() > 0;
    }

    public int availableStock() {
        int totalStock = stock == null ? 0 : stock;
        int reserved = reservedStock == null ? 0 : reservedStock;
        return Math.max(0, totalStock - reserved);
    }
}
