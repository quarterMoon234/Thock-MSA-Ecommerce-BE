package com.thock.back.market.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.domain.Order;
import com.thock.back.market.domain.OrderCancelHistory;
import com.thock.back.market.out.repository.OrderCancelHistoryRepository;
import com.thock.back.market.out.repository.OrderRepository;
import com.thock.back.shared.market.domain.CancelReasonType;
import com.thock.back.shared.product.event.ProductStockReservationFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCompensateStockReservationFailureUseCase {

    private final OrderRepository orderRepository;
    private final OrderCancelHistoryRepository orderCancelHistoryRepository;

    @Transactional
    public void compensate(ProductStockReservationFailedEvent event) {
        Order order = orderRepository.findByOrderNumber(event.orderNumber())
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        String reasonDetail = String.format(
                "재고 예약 실패로 인한 시스템 취소: %s",
                event.reasonMessage()
        );

        boolean cancelled = order.cancelBecauseStockReservationFailed(reasonDetail);
        if (!cancelled) {
            return;
        }

        var histories = order.getItems().stream()
                .map(item -> OrderCancelHistory.ofSystemCancel(
                        order,
                        item,
                        CancelReasonType.STOCK_RESERVATION_FAILED,
                        reasonDetail
                ))
                .toList();

        orderCancelHistoryRepository.saveAll(histories);

        log.warn("재고 예약 실패 보상 처리 완료: orderNumber={}, reasonCode={}",
                event.orderNumber(), event.reasonCode());
    }
}
