package com.thock.back.product.experiment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("experiment")
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiments/stock")
public class ProductStockExperimentController {

    private final ProductStockExperimentService productStockExperimentService;

    @PostMapping("/products")
    public ResponseEntity<ProductStockExperimentProductResponse> createProduct(
            @Valid @RequestBody ProductStockExperimentCreateRequest request
    ) {
        ProductStockExperimentProductResponse response = productStockExperimentService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/reservations")
    public ResponseEntity<ProductStockExperimentReservationResponse> reserve(
            @Valid @RequestBody ProductStockExperimentReservationRequest request
    ) {
        return ResponseEntity.ok(productStockExperimentService.reserve(request));
    }

    @PostMapping("/products/{productId}/redis/rebuild")
    public ResponseEntity<ProductStockExperimentProductResponse> rebuildProductRedisStock(
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok(productStockExperimentService.rebuildProductRedisStock(productId));
    }

    @PostMapping("/redis/rebuild-all")
    public ResponseEntity<ProductStockExperimentRedisRebuildResponse> rebuildAllRedisStock() {
        return ResponseEntity.ok(productStockExperimentService.rebuildAllRedisStock());
    }

    @GetMapping("/products/{productId}/redis")
    public ResponseEntity<ProductStockExperimentRedisStateResponse> getProductRedisState(
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok(productStockExperimentService.getProductRedisState(productId));
    }

    @PostMapping("/metrics/reset")
    public ResponseEntity<Void> resetMetrics() {
        productStockExperimentService.resetMetrics();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics")
    public ResponseEntity<ProductStockExperimentMetricsResponse> getMetrics() {
        return ResponseEntity.ok(productStockExperimentService.getMetrics());
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductStockExperimentProductResponse> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productStockExperimentService.getProduct(productId));
    }
}
