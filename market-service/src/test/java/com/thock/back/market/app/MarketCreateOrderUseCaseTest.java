package com.thock.back.market.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.domain.Cart;
import com.thock.back.market.domain.CartItem;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.domain.OrderState;
import com.thock.back.market.in.dto.req.OrderCreateRequest;
import com.thock.back.market.out.api.dto.ProductInfo;
import com.thock.back.market.out.repository.CartRepository;
import com.thock.back.market.out.repository.MarketMemberRepository;
import com.thock.back.market.out.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MarketCreateOrderUseCaseTest {

    @InjectMocks
    private MarketCreateOrderUseCase marketCreateOrderUseCase;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MarketMemberRepository marketMemberRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private MarketSupport marketSupport;

    @Mock
    private MarketMember buyer;

    @Nested
    @DisplayName("주문 무한 생성 방지 테스트")
    class PreventInfiniteOrderCreationTest {

        @Test
        @DisplayName("미결제 주문이 이미 존재하면 ORDER_PENDING_EXISTS 예외가 발생한다")
        void createOrder_pendingOrderExists_throwsException() {
            // given
            Long memberId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(1L),
                    "12345",
                    "서울시 강남구",
                    "101호"
            );

            given(marketMemberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(buyer));
            given(orderRepository.existsByBuyerIdAndState(memberId, OrderState.PENDING_PAYMENT))
                    .willReturn(true); // 이미 미결제 주문 존재

            // when & then
            assertThatThrownBy(() -> marketCreateOrderUseCase.createOrder(memberId, request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException customException = (CustomException) ex;
                        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.ORDER_PENDING_EXISTS);
                    });

            // 장바구니 조회까지 가지 않아야 함 (조기 차단)
            verify(cartRepository, never()).findByBuyer(any());
        }

        @Test
        @DisplayName("미결제 주문이 없으면 장바구니 조회로 진행된다")
        void createOrder_noPendingOrder_proceedsToCartLookup() {
            // given
            Long memberId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(1L),
                    "12345",
                    "서울시 강남구",
                    "101호"
            );

            given(marketMemberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(buyer));
            given(orderRepository.existsByBuyerIdAndState(memberId, OrderState.PENDING_PAYMENT))
                    .willReturn(false); // 미결제 주문 없음
            given(cartRepository.findByBuyer(buyer)).willReturn(Optional.empty());

            // when & then - 장바구니 없음 예외 발생 (정상 흐름 진행 확인용)
            assertThatThrownBy(() -> marketCreateOrderUseCase.createOrder(memberId, request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException customException = (CustomException) ex;
                        // 장바구니 조회까지 진행됐음을 확인 (CART_ITEM_NOT_FOUND)
                        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
                    });

            // 장바구니 조회가 호출됨
            verify(cartRepository).findByBuyer(buyer);
        }

        @Test
        @DisplayName("연속 주문 생성 시도 차단")
        void createOrder_accessTokenStolen_blocksConsecutiveOrders() {
            // given - 연속으로 주문 생성 시도
            Long memberId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(1L),
                    "12345",
                    "서울시 강남구",
                    "101호"
            );

            given(marketMemberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(buyer));

            // 첫 번째 주문은 성공했다고 가정 (이미 PENDING_PAYMENT 상태)
            given(orderRepository.existsByBuyerIdAndState(memberId, OrderState.PENDING_PAYMENT))
                    .willReturn(true);

            // when - 두 번째 주문 시도
            // then - 차단됨
            assertThatThrownBy(() -> marketCreateOrderUseCase.createOrder(memberId, request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException customException = (CustomException) ex;
                        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.ORDER_PENDING_EXISTS);
                        assertThat(customException.getErrorCode().getMessage())
                                .isEqualTo("이미 결제 대기 중인 주문이 있습니다.");
                    });
        }
    }

    @Nested
    @DisplayName("회원 조회 테스트")
    class MemberLookupTest {

        @Test
        @DisplayName("회원이 존재하지 않으면 CART_USER_NOT_FOUND 예외가 발생한다")
        void createOrder_memberNotFound_throwsException() {
            // given
            Long memberId = 999L;
            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(1L),
                    "12345",
                    "서울시 강남구",
                    "101호"
            );

            given(marketMemberRepository.findByIdForUpdate(memberId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> marketCreateOrderUseCase.createOrder(memberId, request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException customException = (CustomException) ex;
                        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.CART_USER_NOT_FOUND);
                    });

            // 주문 존재 여부 체크도 호출되지 않아야 함
            verify(orderRepository, never()).existsByBuyerIdAndState(any(), any());
        }
    }

    @Nested
    @DisplayName("주문 가능 재고 검증 테스트")
    class AvailableStockValidationTest {

        @Test
        @DisplayName("예약 재고를 제외한 주문 가능 재고보다 많이 주문하면 예외가 발생한다")
        void createOrder_reservedStockIncluded_throwsOutOfStock() {
            Long memberId = 1L;
            MarketMember actualBuyer = new MarketMember(
                    "buyer@test.com",
                    "buyer",
                    MemberRole.USER,
                    MemberState.ACTIVE,
                    memberId,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            Cart cart = new Cart(actualBuyer);
            CartItem cartItem = cart.addItem(100L, 5);
            ReflectionTestUtils.setField(cartItem, "id", 10L);

            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(10L),
                    "12345",
                    "서울시 강남구",
                    "101호"
            );
            ProductInfo product = new ProductInfo(
                    100L,
                    2L,
                    "keyboard",
                    "image",
                    10000L,
                    9000L,
                    5,
                    1,
                    "ON_SALE"
            );

            given(marketMemberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(actualBuyer));
            given(orderRepository.existsByBuyerIdAndState(memberId, OrderState.PENDING_PAYMENT)).willReturn(false);
            given(cartRepository.findByBuyer(actualBuyer)).willReturn(Optional.of(cart));
            given(marketSupport.getProducts(List.of(100L))).willReturn(List.of(product));

            assertThatThrownBy(() -> marketCreateOrderUseCase.createOrder(memberId, request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException customException = (CustomException) ex;
                        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.CART_PRODUCT_OUT_OF_STOCK);
                    });
        }
    }
}
