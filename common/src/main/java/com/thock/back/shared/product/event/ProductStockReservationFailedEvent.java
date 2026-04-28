package com.thock.back.shared.product.event;

import com.thock.back.shared.market.dto.StockOrderItemDto;

import java.util.List;

public record ProductStockReservationFailedEvent(
        String orderNumber,
        List<StockOrderItemDto> items,
        String reasonCode,
        String reasonMessage
) {
}
