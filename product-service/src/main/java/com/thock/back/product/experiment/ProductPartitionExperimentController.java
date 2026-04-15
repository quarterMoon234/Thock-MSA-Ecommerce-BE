package com.thock.back.product.experiment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("experiment")
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiments/partition")
public class ProductPartitionExperimentController {

    private final ProductPartitionExperimentService productPartitionExperimentService;

    @PostMapping("/runs/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ProductPartitionExperimentResetRequest request) {
        productPartitionExperimentService.reset(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/publish")
    public ResponseEntity<ProductPartitionExperimentPublishResponse> publish(
            @Valid @RequestBody ProductPartitionExperimentPublishRequest request
    ) {
        return ResponseEntity.ok(productPartitionExperimentService.publish(request));
    }

    @GetMapping("/runs/{runId}/summary")
    public ResponseEntity<ProductPartitionExperimentSummaryResponse> getSummary(@PathVariable String runId) {
        return ResponseEntity.ok(productPartitionExperimentService.getSummary(runId));
    }
}
