package com.thock.back.market.experiment;

import com.thock.back.market.in.dto.req.CartItemAddRequest;
import com.thock.back.market.in.dto.res.CartItemListResponse;
import com.thock.back.market.in.dto.res.CartItemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("experiment")
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiments/cart-cqrs")
public class CartCqrsExperimentController {

    private final CartCqrsExperimentService cartCqrsExperimentService;

    @PostMapping("/dataset/seed")
    public ResponseEntity<CartCqrsExperimentDatasetResponse> seedDataset(
            @Valid @RequestBody CartCqrsExperimentSeedRequest request
    ) {
        return ResponseEntity.ok(cartCqrsExperimentService.seedDataset(request));
    }

    @GetMapping("/baseline/read/{memberId}")
    public ResponseEntity<CartItemListResponse> getBaselineCartItems(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "0") long productDelayMs
    ) {
        return ResponseEntity.ok(cartCqrsExperimentService.getBaselineCartItems(memberId, productDelayMs));
    }

    @GetMapping("/cqrs/read/{memberId}")
    public ResponseEntity<CartItemListResponse> getCqrsCartItems(@PathVariable Long memberId) {
        return ResponseEntity.ok(cartCqrsExperimentService.getCqrsCartItems(memberId));
    }

    @PostMapping("/baseline/add/{memberId}")
    public ResponseEntity<CartItemResponse> addBaselineCartItem(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "0") long productDelayMs,
            @Valid @RequestBody CartItemAddRequest request
    ) {
        return ResponseEntity.ok(cartCqrsExperimentService.addBaselineCartItem(memberId, request, productDelayMs));
    }

    @PostMapping("/cqrs/add/{memberId}")
    public ResponseEntity<CartItemResponse> addCqrsCartItem(
            @PathVariable Long memberId,
            @Valid @RequestBody CartItemAddRequest request
    ) {
        return ResponseEntity.ok(cartCqrsExperimentService.addCqrsCartItem(memberId, request));
    }
}
