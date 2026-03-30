package com.thock.back.product.out;

import com.thock.back.product.in.dto.ProductSearchResponse;
import com.thock.back.product.in.dto.ProductSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepositoryCustom {
    Page<ProductSearchResponse> search(ProductSearchRequest condition, Pageable pageable);
}
