package com.thock.back.market.experiment;

import com.thock.back.market.in.dto.res.OrderDetailResponse;
import java.util.List;
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
@RequestMapping("/api/v1/experiments/order-query")
public class OrderQueryExperimentController {

    private final OrderQueryExperimentService orderQueryExperimentService;

    @PostMapping("/dataset/reset")
    public ResponseEntity<OrderQueryExperimentDatasetResponse> resetDataset(
            @RequestParam(required = false) Long memberId
    ) {
        return ResponseEntity.ok(orderQueryExperimentService.resetDataset(memberId));
    }

    @PostMapping("/dataset/seed")
    public ResponseEntity<OrderQueryExperimentDatasetResponse> seedDataset(
            @RequestBody(required = false) OrderQueryExperimentSeedRequest request
    ) {
        return ResponseEntity.ok(orderQueryExperimentService.seedDataset(request));
    }

    @GetMapping("/dataset")
    public ResponseEntity<OrderQueryExperimentDatasetResponse> getDataset(
            @RequestParam(required = false) Long memberId
    ) {
        return ResponseEntity.ok(orderQueryExperimentService.getDataset(memberId));
    }

    @GetMapping("/baseline/{memberId}")
    public ResponseEntity<List<OrderDetailResponse>> getBaselineOrders(
            @PathVariable Long memberId
    ) {
        return ResponseEntity.ok(orderQueryExperimentService.getMyOrdersBaseline(memberId));
    }

    @GetMapping("/optimized/{memberId}")
    public ResponseEntity<List<OrderDetailResponse>> getOptimizedOrders(
            @PathVariable Long memberId
    ) {
        return ResponseEntity.ok(orderQueryExperimentService.getMyOrdersOptimized(memberId));
    }
}
