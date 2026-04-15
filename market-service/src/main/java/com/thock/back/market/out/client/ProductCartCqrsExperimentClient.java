package com.thock.back.market.out.client;

import com.thock.back.market.config.FeignConfig;
import com.thock.back.market.out.api.dto.ProductInfo;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "product-cart-cqrs-experiment-client",
        url = "${custom.global.productServiceUrl}/api/v1/experiments/cart-cqrs/internal/products",
        configuration = FeignConfig.class
)
public interface ProductCartCqrsExperimentClient {

    @PostMapping("/list")
    List<ProductInfo> getProducts(
            @RequestParam long delayMs,
            @RequestBody List<Long> productIds
    );
}
