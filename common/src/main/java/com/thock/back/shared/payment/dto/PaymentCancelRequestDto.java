package com.thock.back.shared.payment.dto;

import jakarta.annotation.Nullable;

public record PaymentCancelRequestDto(
        String orderId,
        String cancelReason,
        @Nullable Long amount // amount == 0 이면 전액 환불
) {}
