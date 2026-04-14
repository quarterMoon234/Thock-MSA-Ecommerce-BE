package com.thock.back.market.in.dto.res;

import com.thock.back.market.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record InternalOrderSummaryResponse(
        @Schema(description = "주문 ID", example = "1")
        Long orderId,

        @Schema(description = "주문 번호", example = "ORDER-20250129-A1B2C3D4")
        String orderNumber,

        @Schema(description = "주문 상태", example = "PAYMENT_COMPLETED")
        String state,

        @Schema(description = "총 결제 금액", example = "240000")
        Long totalSalePrice,

        @Schema(description = "주문 생성 시간", example = "2025-01-29T14:30:00")
        LocalDateTime createdAt
) {
    public static InternalOrderSummaryResponse from(Order order) {
        return new InternalOrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getState().name(),
                order.getTotalSalePrice(),
                order.getCreatedAt()
        );
    }
}
