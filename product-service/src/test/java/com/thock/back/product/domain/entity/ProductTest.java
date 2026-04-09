package com.thock.back.product.domain.entity;

import com.thock.back.product.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    @DisplayName("salePrice가 0이면 생성 시 price를 할인 가격으로 사용한다")
    void usesPriceWhenSalePriceIsZeroOnCreate() {
        Product product = Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name("keyboard")
                .description("desc")
                .price(10_000L)
                .salePrice(0L)
                .stock(10)
                .imageUrl("https://example.com/product.png")
                .detail(null)
                .build();

        assertThat(product.getSalePrice()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("salePrice가 0이면 수정 시 price를 할인 가격으로 사용한다")
    void usesPriceWhenSalePriceIsZeroOnModify() {
        Product product = Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name("keyboard")
                .description("desc")
                .price(10_000L)
                .salePrice(9_000L)
                .stock(10)
                .imageUrl("https://example.com/product.png")
                .detail(null)
                .build();

        product.modify(
                "updated keyboard",
                12_000L,
                0L,
                20,
                Category.KEYBOARD,
                "updated desc",
                "https://example.com/updated-product.png",
                null
        );

        assertThat(product.getSalePrice()).isEqualTo(12_000L);
    }
}
