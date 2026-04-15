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
@RequestMapping("/api/v1/experiments/inbox")
public class ProductInboxExperimentController {

    private final ProductInboxExperimentService productInboxExperimentService;

    @PostMapping("/runs/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ProductInboxExperimentResetRequest request) {
        productInboxExperimentService.reset(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/publish")
    public ResponseEntity<ProductInboxExperimentPublishResponse> publish(
            @Valid @RequestBody ProductInboxExperimentPublishRequest request
    ) {
        ProductInboxExperimentPublishResponse response = productInboxExperimentService.publish(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/runs/{runId}/summary")
    public ResponseEntity<ProductInboxExperimentSummaryResponse> getSummary(@PathVariable String runId) {
        return ResponseEntity.ok(productInboxExperimentService.getSummary(runId));
    }
}
