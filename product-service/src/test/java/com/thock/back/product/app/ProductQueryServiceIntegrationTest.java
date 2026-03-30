package com.thock.back.product.app;

import com.thock.back.product.ProductServiceApplication;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.dto.ProductSearchRequest;
import com.thock.back.product.in.dto.ProductSearchResponse;
import com.thock.back.product.in.dto.ProductSearchSortType;
import com.thock.back.product.out.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ProductServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:product-query-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class ProductQueryServiceIntegrationTest {

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private ProductRepository productRepository;

    @AfterEach
    void tearDown() {
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("키워드와 가격 범위 조건으로 상품을 검색한다")
    void searchProducts_withKeywordAndPriceRange() {
        productRepository.save(createProduct("기계식 키보드", 15_000L));
        productRepository.save(createProduct("무선 키보드", 30_000L));
        productRepository.save(createProduct("장패드", 8_000L));

        ProductSearchRequest condition = new ProductSearchRequest(
                "키보드",
                null,
                10_000L,
                20_000L,
                null,
                ProductSearchSortType.LATEST
        );

        Page<ProductSearchResponse> result =
                productQueryService.searchProducts(condition, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(ProductSearchResponse::name)
                .containsExactly("기계식 키보드");
        assertThat(result.getContent().get(0).seller()).isEqualTo("판매자 1");
    }

    @Test
    @DisplayName("가격 오름차순으로 정렬하고 페이지네이션한다")
    void searchProducts_withPriceAscSortAndPaging() {
        productRepository.save(createProduct("키보드 A", 30_000L));
        productRepository.save(createProduct("키보드 B", 10_000L));
        productRepository.save(createProduct("키보드 C", 20_000L));

        ProductSearchRequest condition = new ProductSearchRequest(
                "키보드",
                null,
                null,
                null,
                null,
                ProductSearchSortType.PRICE_ASC
        );

        Page<ProductSearchResponse> result =
                productQueryService.searchProducts(condition, PageRequest.of(0, 2));

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .extracting(ProductSearchResponse::name)
                .containsExactly("키보드 B", "키보드 C");
    }

    private Product createProduct(String name, Long price) {
        return Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name(name)
                .description(name + " 설명")
                .price(price)
                .salePrice(price)
                .stock(100)
                .imageUrl("https://example.com/product.png")
                .detail(null)
                .build();
    }
}
