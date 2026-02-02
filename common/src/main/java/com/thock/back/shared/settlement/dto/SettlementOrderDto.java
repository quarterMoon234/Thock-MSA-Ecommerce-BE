package com.thock.back.shared.settlement.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record SettlementOrderDto(
        // 1. 식별자
        Long orderId,
        Long orderItemId, // OrderItem의 ID (상세 대조용)

        // 2. 상품 정보
        Long productId,
        String productName,
        Integer quantity, // 수량 (OrderItem에 있어서 추가함)

        // 3. 금액 정보 (OrderItem 필드명과 일치시킴 -> 매핑 실수 방지)
        Long totalSalePrice,    // 결제 금액 (할인 적용 후 실 판매가)
        Long payoutAmount,      // 판매자 정산 금액 (이미 수수료 떼인 값)
        Long feeAmount,         // 수수료

        // 4. 시간 정보
        LocalDateTime confirmedAt   // 구매 확정일 (쿼리용)
) {}
