package com.thock.back.product.in.dto;

import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.ProductState;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 검색 조건")
public record ProductSearchRequest(
        @Schema(description = "검색어") String keyword,
        @Schema(description = "카테고리") Category category,
        @Schema(description = "최소 가격") Long minPrice,
        @Schema(description = "최대 가격") Long maxPrice,
        @Schema(description = "판매 상태") ProductState state,
        @Schema(description = "정렬 기준") ProductSearchSortType sort
) {
}
