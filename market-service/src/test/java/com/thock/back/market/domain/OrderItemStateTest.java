package com.thock.back.market.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemStateTest {

    @Test
    @DisplayName("PENDING_PAYMENT 상태는 취소 가능하다")
    void pendingPayment_isCancellable() {
        assertThat(OrderItemState.PENDING_PAYMENT.isCancellable()).isTrue();
    }

    @Test
    @DisplayName("PAYMENT_COMPLETED 상태는 취소 가능하다")
    void paymentCompleted_isCancellable() {
        assertThat(OrderItemState.PAYMENT_COMPLETED.isCancellable()).isTrue();
    }

    @Test
    @DisplayName("PREPARING 상태는 취소 가능하다")
    void preparing_isCancellable() {
        assertThat(OrderItemState.PREPARING.isCancellable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemState.class, names = {"SHIPPING", "DELIVERED", "CONFIRMED", "CANCELLED", "REFUND_REQUESTED", "REFUNDED"})
    @DisplayName("배송중 이후 상태는 취소 불가능하다")
    void shippingAndAfter_isNotCancellable(OrderItemState state) {
        assertThat(state.isCancellable()).isFalse();
    }

    @Test
    @DisplayName("DELIVERED 상태만 구매 확정 가능하다")
    void onlyDelivered_isConfirmable() {
        assertThat(OrderItemState.DELIVERED.isConfirmable()).isTrue();

        // 다른 상태는 구매 확정 불가
        assertThat(OrderItemState.PAYMENT_COMPLETED.isConfirmable()).isFalse();
        assertThat(OrderItemState.SHIPPING.isConfirmable()).isFalse();
        assertThat(OrderItemState.CONFIRMED.isConfirmable()).isFalse();
    }

    @Test
    @DisplayName("DELIVERED, CONFIRMED 상태는 환불 요청 가능하다")
    void deliveredAndConfirmed_isRefundable() {
        assertThat(OrderItemState.DELIVERED.isRefundable()).isTrue();
        assertThat(OrderItemState.CONFIRMED.isRefundable()).isTrue();

        // 다른 상태는 환불 요청 불가
        assertThat(OrderItemState.PAYMENT_COMPLETED.isRefundable()).isFalse();
        assertThat(OrderItemState.SHIPPING.isRefundable()).isFalse();
    }
}
