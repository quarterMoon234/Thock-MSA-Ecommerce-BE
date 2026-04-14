package com.thock.back.member.out.client.dto;

import java.time.LocalDateTime;

public record MarketOrderSummaryDto(
        Long orderId,
        String orderNumber,
        String state,
        Long totalPrice,
        LocalDateTime createdAt
) {
}
