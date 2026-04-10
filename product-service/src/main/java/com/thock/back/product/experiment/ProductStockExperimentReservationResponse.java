package com.thock.back.product.experiment;

public record ProductStockExperimentReservationResponse(
        String orderNumber,
        Long productId,
        ProductStockExperimentReservationOutcome outcome,
        String reason
) {
    public static ProductStockExperimentReservationResponse reserved(String orderNumber, Long productId) {
        return new ProductStockExperimentReservationResponse(
                orderNumber,
                productId,
                ProductStockExperimentReservationOutcome.RESERVED,
                null
        );
    }

    public static ProductStockExperimentReservationResponse rejected(
            String orderNumber,
            Long productId,
            String reason
    ) {
        return new ProductStockExperimentReservationResponse(
                orderNumber,
                productId,
                ProductStockExperimentReservationOutcome.REJECTED,
                reason
        );
    }
}
