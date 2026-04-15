package com.thock.back.product.experiment;

import com.thock.back.product.app.ProductQueryService;
import com.thock.back.product.in.dto.internal.ProductInternalResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class ProductCartCqrsExperimentService {

    private final ProductQueryService productQueryService;

    @Transactional(readOnly = true)
    public List<ProductInternalResponse> getProductsByIds(List<Long> productIds, long delayMs) {
        applyDelay(delayMs);
        return productQueryService.getProductsByIds(productIds);
    }

    private void applyDelay(long delayMs) {
        if (delayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while delaying cart CQRS experiment", e);
        }
    }
}
