package com.thock.back.product.experiment;

import com.thock.back.product.in.dto.internal.ProductInternalResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("experiment")
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiments/cart-cqrs/internal/products")
public class ProductCartCqrsExperimentController {

    private final ProductCartCqrsExperimentService productCartCqrsExperimentService;

    @PostMapping("/list")
    public ResponseEntity<List<ProductInternalResponse>> getProductsByIds(
            @RequestParam(defaultValue = "0") long delayMs,
            @RequestBody @NotEmpty List<@NotNull Long> productIds
    ) {
        return ResponseEntity.ok(productCartCqrsExperimentService.getProductsByIds(productIds, delayMs));
    }
}
